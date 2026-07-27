package com.thaap.payments.dispute;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DisputeOperationsMetrics {

    private final MeterRegistry registry;

    public DisputeOperationsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String result) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(operation, result, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(operation, result, status == TransactionSynchronization.STATUS_COMMITTED
                        ? "committed" : "rolled_back");
            }
        });
    }

    private void increment(String operation, String result, String transaction) {
        registry.counter("thaap.dispute.operations", "operation", operation,
                "result", result, "transaction", transaction).increment();
    }
}
