package com.thaap.payments.riskcontext;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RiskContextMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RiskContextMetrics metrics = new RiskContextMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void observationsExposeOnlyClosedPresenceAndTransactionDimensionsAfterCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        metrics.recordObservation(true);
        assertThat(registry.find("thaap.risk_context.requests").counters()).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(registry.get("thaap.risk_context.requests")
                .tags("presence", "PRESENT", "transaction", "rolled_back").counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters().getFirst().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrderElementsOf(Set.of("presence", "transaction"));
    }

    @Test
    void rejectionAndReadinessMetricsUseClosedCausesWithoutIdentityTags() {
        metrics.recordRejection("INVALID_IP");
        metrics.recordReadinessFailure();

        assertThat(registry.get("thaap.risk_context.requests.rejected")
                .tag("cause", "INVALID_IP").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("thaap.risk_context.readiness.failures")
                .tag("cause", "KEY_UNAVAILABLE").counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("cause")));
    }
}
