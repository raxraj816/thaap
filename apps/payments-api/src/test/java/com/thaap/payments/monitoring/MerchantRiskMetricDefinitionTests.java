package com.thaap.payments.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MonitoringRequest;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MonitoringWindow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

class MerchantRiskMetricDefinitionTests {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00.123456Z");

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void defaultWindowIsExactHalfOpenLiveOnlyAndMicrosecondBounded() {
        MerchantRiskMonitoringQueryService service = service();

        MonitoringWindow window = service.normalize(null);

        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-07-24T12:00:00.123456Z"));
        assertThat(window.windowEnd()).isEqualTo(NOW);
        assertThat(window.asOf()).isEqualTo(NOW);
        assertThat(window.trafficOriginPolicy())
                .isEqualTo(MonitoringTrafficOriginPolicy.MERCHANT_API_ONLY);
    }

    @Test
    void explicitWindowCanonicalizesToPersistencePrecisionAndPreservesVisibleOriginPolicy() {
        MerchantRiskMonitoringQueryService service = service();
        Instant start = Instant.parse("2026-07-25T10:00:00.123456789Z");
        Instant end = Instant.parse("2026-07-25T11:00:00.987654321Z");

        MonitoringWindow window = service.normalize(new MonitoringRequest(start, end, NOW,
                MonitoringTrafficOriginPolicy.INCLUDE_SYNTHETIC));

        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-07-25T10:00:00.123456Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-07-25T11:00:00.987654Z"));
        assertThat(window.trafficOriginPolicy())
                .isEqualTo(MonitoringTrafficOriginPolicy.INCLUDE_SYNTHETIC);
    }

    @Test
    void subMicrosecondWindowThatCollapsesAtPersistencePrecisionFailsClosed() {
        MerchantRiskMonitoringQueryService service = service();
        Instant start = Instant.parse("2026-07-25T10:00:00.123456100Z");
        Instant end = Instant.parse("2026-07-25T10:00:00.123456900Z");

        assertBadRequest(service, start, end, NOW, "windowStart must be before windowEnd");
    }

    @Test
    void invalidTimeGrammarPartitionsFailClosed() {
        MerchantRiskMonitoringQueryService service = service();
        assertBadRequest(service, NOW, NOW, NOW, "windowStart must be before windowEnd");
        assertBadRequest(service, NOW.minusSeconds(1), NOW.plusSeconds(1), NOW,
                "windowEnd must be at or before asOf");
        assertBadRequest(service, NOW.minusSeconds(1), NOW, NOW.plusSeconds(1),
                "asOf cannot be in the future");
        assertBadRequest(service, NOW.minusSeconds(90L * 24 * 60 * 60 + 1), NOW, NOW,
                "Monitoring window must not exceed 90 days");
        MonitoringWindow maximum = service.normalize(new MonitoringRequest(
                NOW.minusSeconds(90L * 24 * 60 * 60), NOW, NOW, null));
        assertThat(maximum.windowStart()).isEqualTo(NOW.minusSeconds(90L * 24 * 60 * 60));
    }

    @Test
    void telemetryUsesOnlyClosedTagsAndReportsRollbackAfterCompletion() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantRiskMonitoringMetrics metrics = new MerchantRiskMonitoringMetrics(registry);
        metrics.recordRead(MerchantRiskMetricCode.CONFIRMED_FRAUD_RATE,
                MonitoringCoverageStatus.PARTIAL_LABEL_COVERAGE);
        metrics.recordRequest("SUMMARY", "INVALID_REQUEST");
        TransactionSynchronizationManager.initSynchronization();
        metrics.recordSnapshotAfterCompletion("SNAPSHOT_WITH_RECOMMENDATION");
        assertThat(registry.find("thaap.risk.monitoring.snapshots").counters()).isEmpty();

        TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations().getFirst();
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        Counter read = registry.find("thaap.risk.monitoring.reads")
                .tags("metric", "CONFIRMED_FRAUD_RATE",
                        "coverage", "PARTIAL_LABEL_COVERAGE").counter();
        Counter rollback = registry.find("thaap.risk.monitoring.snapshots")
                .tags("outcome", "SNAPSHOT_WITH_RECOMMENDATION",
                        "transaction", "rolled_back").counter();
        Counter invalidRequest = registry.find("thaap.risk.monitoring.requests")
                .tags("operation", "SUMMARY", "result", "INVALID_REQUEST").counter();
        assertThat(read).isNotNull();
        assertThat(read.count()).isEqualTo(1);
        assertThat(rollback).isNotNull();
        assertThat(rollback.count()).isEqualTo(1);
        assertThat(invalidRequest).isNotNull();
        assertThat(invalidRequest.count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("metric", "coverage", "outcome", "transaction",
                                "operation", "result")));
    }

    private MerchantRiskMonitoringQueryService service() {
        return new MerchantRiskMonitoringQueryService(mock(JdbcTemplate.class),
                Clock.fixed(NOW, ZoneOffset.UTC), mock(MerchantRiskMonitoringReadProbe.class));
    }

    private void assertBadRequest(MerchantRiskMonitoringQueryService service,
            Instant start, Instant end, Instant asOf, String reason) {
        assertThatThrownBy(() -> service.normalize(new MonitoringRequest(start, end, asOf, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(reason);
    }
}
