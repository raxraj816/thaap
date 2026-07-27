package com.thaap.payments.developer;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DeveloperScenarioMetrics {

    private final MeterRegistry registry;

    public DeveloperScenarioMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String scenarioType, String outcome) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(operation, scenarioType, outcome, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(operation, scenarioType, outcome,
                        status == TransactionSynchronization.STATUS_COMMITTED ? "committed" : "rolled_back");
            }
        });
    }

    private void increment(String operation, String scenarioType, String outcome, String transaction) {
        registry.counter("thaap.developer.scenario.operations",
                "operation", operation, "scenario_type", scenarioType,
                "outcome", outcome, "transaction", transaction).increment();
    }
}
