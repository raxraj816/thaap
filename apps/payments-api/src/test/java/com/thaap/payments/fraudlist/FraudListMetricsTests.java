package com.thaap.payments.fraudlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.fraud.FraudDecisionAction;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FraudListMetricsTests {

    @Test
    void evaluationManagementAndLatencyMetricsWaitForCommitAndUseOnlyClosedDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FraudListMetrics metrics = new FraudListMetrics(registry);
        FraudListEvaluation evaluation = evaluation(FraudListIdentityType.EMAIL, true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.recordEvaluation(evaluation);
            metrics.recordEvaluationLatency(12_000L);
            metrics.recordManagement("ENTRY_CREATED");
            assertThat(registry.getMeters()).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(registry.counter("thaap.fraud.list.evaluations",
                "identity_type", "EMAIL", "result", "MATCH", "transaction", "committed").count()).isOne();
        assertThat(registry.timer("thaap.fraud.list.evaluation.latency", "transaction", "committed").count()).isOne();
        assertThat(registry.counter("thaap.fraud.list.management",
                "operation", "ENTRY_CREATED", "transaction", "committed").count()).isOne();
        assertClosedTags(registry.getMeters());
    }

    @Test
    void rollbackIsExplicitAndNeverInflatesCommittedMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FraudListMetrics metrics = new FraudListMetrics(registry);
        FraudListEvaluation evaluation = evaluation(FraudListIdentityType.IP, false);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.recordEvaluation(evaluation);
            metrics.recordEvaluationLatency(9_000L);
            metrics.recordManagement("VERSION_DEACTIVATED");
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(registry.counter("thaap.fraud.list.evaluations",
                "identity_type", "IP", "result", "NO_MATCH", "transaction", "rolled_back").count()).isOne();
        assertThat(registry.find("thaap.fraud.list.evaluations").tag("transaction", "committed").counter()).isNull();
        assertThat(registry.timer("thaap.fraud.list.evaluation.latency", "transaction", "rolled_back").count()).isOne();
        assertThat(registry.counter("thaap.fraud.list.management",
                "operation", "VERSION_DEACTIVATED", "transaction", "rolled_back").count()).isOne();
        assertClosedTags(registry.getMeters());
    }

    private FraudListEvaluation evaluation(FraudListIdentityType type, boolean matched) {
        return new FraudListEvaluation(UUID.randomUUID(), UUID.randomUUID(), 1, "SAFE_LIST_CODE", type,
                matched ? UUID.randomUUID() : null, matched ? FraudDecisionAction.BLOCK : null,
                matched ? FraudListReasonCode.KNOWN_FRAUD : null, Instant.parse("2026-07-14T00:00:00Z"));
    }

    private void assertClosedTags(List<Meter> meters) {
        assertThat(meters).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("identity_type", "result", "transaction", "operation");
                assertThat(tag.getValue()).matches("[A-Z_]+|committed|rolled_back");
            });
            assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getValue().contains("SAFE_LIST_CODE"));
        });
    }
}
