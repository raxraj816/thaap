package com.thaap.payments.webhook;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class WebhookOperationsMetrics {

    private final MeterRegistry registry;

    public WebhookOperationsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String outcome, String actorType) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(operation, outcome, actorType, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(operation, outcome, actorType,
                        status == TransactionSynchronization.STATUS_COMMITTED
                                ? "committed" : "rolled_back");
            }
        });
    }

    private void increment(String operation, String outcome, String actorType, String transaction) {
        registry.counter("thaap.webhook.operations",
                "operation", operation, "outcome", outcome,
                "actor_type", actorType, "transaction", transaction).increment();
    }
}
