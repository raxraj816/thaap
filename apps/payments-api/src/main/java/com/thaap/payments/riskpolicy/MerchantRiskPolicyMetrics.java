package com.thaap.payments.riskpolicy;

import com.thaap.payments.fraud.FraudDecisionAction;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class MerchantRiskPolicyMetrics {

    private final MeterRegistry registry;

    public MerchantRiskPolicyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordManagement(String resource, String operation) {
        afterCompletion(
                () -> recordManagement(resource, operation, "committed"),
                () -> recordManagement(resource, operation, "rolled_back"));
    }

    public void recordAssessment(MerchantPolicyAssessment assessment, long elapsedNanos) {
        MerchantRiskConfigurationStatus status = assessment.configuration().status();
        FraudDecisionAction candidate = assessment.strongestCandidate();
        String result = status == MerchantRiskConfigurationStatus.UNAVAILABLE
                ? "UNAVAILABLE" : assessment.hasMatch() ? "MATCH" : "NO_MATCH";
        String action = candidate == null ? "NONE" : candidate.name();
        afterCompletion(
                () -> recordAssessment(status, result, action, elapsedNanos, "committed"),
                () -> recordAssessment(status, result, action, elapsedNanos, "rolled_back"));
    }

    public void recordPreview(boolean matched, FraudDecisionAction candidate) {
        registry.counter("thaap.risk.policy.preview",
                "result", matched ? "MATCH" : "NO_MATCH",
                "candidate_action", candidate == null ? "NONE" : candidate.name()).increment();
    }

    private void recordManagement(String resource, String operation, String transaction) {
        registry.counter("thaap.risk.policy.management",
                "resource", resource,
                "operation", operation,
                "transaction", transaction).increment();
    }

    private void recordAssessment(MerchantRiskConfigurationStatus status, String result,
            String candidateAction, long elapsedNanos, String transaction) {
        registry.counter("thaap.risk.policy.evaluations",
                "configuration_status", status.name(),
                "result", result,
                "candidate_action", candidateAction,
                "transaction", transaction).increment();
        registry.timer("thaap.risk.policy.evaluation.latency",
                "configuration_status", status.name(),
                "transaction", transaction)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private void afterCompletion(Runnable committed, Runnable rolledBack) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    committed.run();
                } else {
                    rolledBack.run();
                }
            }
        });
    }
}
