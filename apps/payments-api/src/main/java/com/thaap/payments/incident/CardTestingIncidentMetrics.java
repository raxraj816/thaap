package com.thaap.payments.incident;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CardTestingIncidentMetrics {
    private final MeterRegistry registry;

    public CardTestingIncidentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAfterCompletion(String operation, String outcome) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(operation, outcome);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                record(operation, status == TransactionSynchronization.STATUS_COMMITTED
                        ? outcome : "ROLLED_BACK");
            }
        });
    }

    private void record(String operation, String outcome) {
        registry.counter("thaap.card_testing_incidents", "operation", operation,
                "outcome", outcome).increment();
    }
}
