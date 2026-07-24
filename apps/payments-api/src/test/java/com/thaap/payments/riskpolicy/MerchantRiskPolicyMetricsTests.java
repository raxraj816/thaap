package com.thaap.payments.riskpolicy;

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

class MerchantRiskPolicyMetricsTests {

    @Test
    void managementAndEvaluationMetricsWaitForCommitAndUseOnlyClosedDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantRiskPolicyMetrics metrics = new MerchantRiskPolicyMetrics(registry);
        MerchantPolicyAssessment assessment = assessment(MerchantRiskConfigurationStatus.ACTIVE);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.recordManagement("POLICY", "ACTIVATED");
            metrics.recordAssessment(assessment, 25_000L);
            assertThat(registry.getMeters()).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(registry.counter("thaap.risk.policy.management",
                "resource", "POLICY", "operation", "ACTIVATED",
                "transaction", "committed").count()).isOne();
        assertThat(registry.counter("thaap.risk.policy.evaluations",
                "configuration_status", "ACTIVE", "result", "NO_MATCH",
                "candidate_action", "NONE", "transaction", "committed").count()).isOne();
        assertThat(registry.timer("thaap.risk.policy.evaluation.latency",
                "configuration_status", "ACTIVE", "transaction", "committed").count()).isOne();
        assertClosedTags(registry.getMeters());
    }

    @Test
    void rollbackAndUnavailableAssessmentAreExplicit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantRiskPolicyMetrics metrics = new MerchantRiskPolicyMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.recordManagement("PROFILE", "RETIRED");
            metrics.recordAssessment(assessment(MerchantRiskConfigurationStatus.UNAVAILABLE), 11_000L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(registry.counter("thaap.risk.policy.evaluations",
                "configuration_status", "UNAVAILABLE", "result", "UNAVAILABLE",
                "candidate_action", FraudDecisionAction.REVIEW.name(),
                "transaction", "rolled_back").count()).isOne();
        assertThat(registry.find("thaap.risk.policy.evaluations")
                .tag("transaction", "committed").counter()).isNull();
        assertClosedTags(registry.getMeters());
    }

    @Test
    void fixturePreviewRecordsNoIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantRiskPolicyMetrics metrics = new MerchantRiskPolicyMetrics(registry);

        metrics.recordPreview(true, FraudDecisionAction.BLOCK);

        assertThat(registry.counter("thaap.risk.policy.preview",
                "result", "MATCH", "candidate_action", "BLOCK").count()).isOne();
        assertClosedTags(registry.getMeters());
    }

    private MerchantPolicyAssessment assessment(MerchantRiskConfigurationStatus status) {
        MerchantRiskConfiguration configuration = status == MerchantRiskConfigurationStatus.UNAVAILABLE
                ? MerchantRiskConfiguration.unavailable(UUID.randomUUID(), Instant.parse("2026-07-14T00:00:00Z"))
                : new MerchantRiskConfiguration(UUID.randomUUID(), status, null, MerchantRiskTier.STANDARD,
                        MerchantRiskProfileSource.APPLICATION_DEFAULT, null, null, 0, List.of(),
                        Instant.parse("2026-07-14T00:00:00Z"));
        return new MerchantPolicyAssessment(configuration, List.of());
    }

    private void assertClosedTags(List<Meter> meters) {
        assertThat(meters).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("resource", "operation", "configuration_status",
                    "result", "candidate_action", "transaction");
            assertThat(tag.getValue()).matches("[A-Z_]+|committed|rolled_back");
            assertThat(tag.getValue()).doesNotContain("-");
        }));
    }
}
