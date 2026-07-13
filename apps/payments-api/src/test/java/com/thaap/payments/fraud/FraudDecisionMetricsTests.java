package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.tenant.TrafficOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FraudDecisionMetricsTests {

    @Test
    void committedMetricsUseOnlyClosedOperationalDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FraudDecisionMetrics metrics = new FraudDecisionMetrics(registry);
        ConfirmDecision decision = blockDecision();

        metrics.observeDecisionTransaction(decision, TrafficOrigin.MERCHANT_API, 1_000L);
        metrics.observeReviewCancellation(TrafficOrigin.MERCHANT_API);
        metrics.recordFailure("EVALUATION", "ENGINE_FAILURE");

        assertThat(registry.get("fraud_decisions_total").tags("action", "BLOCK", "risk_level", "HIGH",
                "traffic_origin", "MERCHANT_API").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud_rule_evaluations_total").tags("rule_code",
                "BLOCK_USD_EXTREME_AMOUNT_V1", "outcome", "MATCH").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud_review_cases_canceled_total")
                .tags("traffic_origin", "MERCHANT_API").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud_decision_failures_total")
                .tags("stage", "EVALUATION", "cause", "ENGINE_FAILURE", "feature_code", "NONE")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .doesNotContain("merchant_id", "payment_intent_id", "payment_attempt_id", "fraud_decision_id",
                        "manual_review_case_id", "feature_value", "idempotency_key", "reason_text");
    }

    @Test
    void transactionRollbackEmitsOnlyBoundedFailureSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FraudDecisionMetrics metrics = new FraudDecisionMetrics(registry);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.observeDecisionTransaction(blockDecision(), TrafficOrigin.SYNTHETIC_SCENARIO, 1_000L);
            metrics.observeReviewCancellation(TrafficOrigin.SYNTHETIC_SCENARIO);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(2);
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(registry.find("fraud_decisions_total").counter()).isNull();
        assertThat(registry.find("fraud_review_cases_canceled_total").counter()).isNull();
        assertThat(registry.get("fraud_decision_failures_total")
                .tags("stage", "TRANSACTION", "cause", "ROLLED_BACK", "feature_code", "NONE")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud_decision_failures_total")
                .tags("stage", "REVIEW_CANCELLATION", "cause", "TRANSACTION_ROLLED_BACK",
                        "feature_code", "NONE")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud_review_case_cancellation_failures_total")
                .tags("status", "ROLLED_BACK", "traffic_origin", "SYNTHETIC_SCENARIO",
                        "cause", "TRANSACTION_ROLLED_BACK")
                .counter().count()).isEqualTo(1.0);
    }

    private ConfirmDecision blockDecision() {
        RuleEvaluation evaluation = new RuleEvaluation(UUID.randomUUID(), "BLOCK_USD_EXTREME_AMOUNT_V1", "rules_v1",
                100, true, FraudDecisionAction.BLOCK, FraudDecisionReasonCode.EXTREME_AMOUNT,
                RuleEvaluationOutcome.MATCH, 12L);
        return new ConfirmDecision(FraudDecisionAction.BLOCK, new BigDecimal("0.9500"),
                FraudRiskLevel.HIGH, "rules_v1", null, List.of(FraudDecisionReasonCode.EXTREME_AMOUNT),
                List.of(evaluation));
    }
}
