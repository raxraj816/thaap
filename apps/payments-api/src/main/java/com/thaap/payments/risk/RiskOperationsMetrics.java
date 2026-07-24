package com.thaap.payments.risk;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class RiskOperationsMetrics {

    private final MeterRegistry meterRegistry;

    public RiskOperationsMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordCommitted(String operation, String outcome) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(operation, outcome, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(operation, outcome,
                        status == TransactionSynchronization.STATUS_COMMITTED ? "committed" : "rolled_back");
            }
        });
    }

    private void increment(String operation, String outcome, String transactionOutcome) {
        meterRegistry.counter("thaap.risk.operations", "operation", operation,
                "outcome", outcome, "transaction", transactionOutcome).increment();
    }
}
