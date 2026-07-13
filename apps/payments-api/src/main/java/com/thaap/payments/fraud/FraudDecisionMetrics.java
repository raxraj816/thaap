package com.thaap.payments.fraud;

import com.thaap.payments.tenant.TrafficOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class FraudDecisionMetrics {

    private final MeterRegistry registry;

    public FraudDecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void observeDecisionTransaction(ConfirmDecision decision, TrafficOrigin origin, long elapsedNanos) {
        afterCompletion(
                () -> recordDecisionNow(decision, origin, elapsedNanos),
                () -> recordFailure("TRANSACTION", "ROLLED_BACK"));
    }

    private void recordDecisionNow(ConfirmDecision decision, TrafficOrigin origin, long elapsedNanos) {
        registry.counter("fraud_decisions_total", "action", decision.action().name(),
                "risk_level", decision.riskLevel().name(), "traffic_origin", origin.name()).increment();
        registry.timer("fraud_decision_latency_seconds", "action", decision.action().name())
                .record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
        for (RuleEvaluation evaluation : decision.ruleEvaluations()) {
            registry.counter("fraud_rule_evaluations_total", "rule_code", evaluation.ruleCode(),
                    "outcome", evaluation.outcome().name()).increment();
        }
        if (decision.action() == FraudDecisionAction.REVIEW) {
            registry.counter("fraud_review_cases_opened_total", "traffic_origin", origin.name()).increment();
        }
    }

    public void observeReviewCancellation(TrafficOrigin origin) {
        afterCompletion(
                () -> registry.counter("fraud_review_cases_canceled_total",
                        "traffic_origin", origin.name()).increment(),
                () -> {
                    registry.counter("fraud_review_case_cancellation_failures_total",
                            "status", "ROLLED_BACK", "traffic_origin", origin.name(),
                            "cause", "TRANSACTION_ROLLED_BACK").increment();
                    recordFailure("REVIEW_CANCELLATION", "TRANSACTION_ROLLED_BACK");
                });
    }

    public void recordFailure(String stage, String cause) {
        registry.counter("fraud_decision_failures_total", "stage", stage, "cause", cause,
                "feature_code", "NONE").increment();
    }

    private void afterCompletion(Runnable committedRecorder, Runnable rolledBackRecorder) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committedRecorder.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    committedRecorder.run();
                } else {
                    rolledBackRecorder.run();
                }
            }
        });
    }
}
