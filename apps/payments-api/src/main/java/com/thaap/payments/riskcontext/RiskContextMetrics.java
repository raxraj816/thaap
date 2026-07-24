package com.thaap.payments.riskcontext;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class RiskContextMetrics {

    private final MeterRegistry registry;

    public RiskContextMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordObservation(boolean present) {
        String presence = present ? "PRESENT" : "ABSENT";
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordTransaction(presence, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                recordTransaction(presence, status == TransactionSynchronization.STATUS_COMMITTED
                        ? "committed" : "rolled_back");
            }
        });
    }

    public void recordRejection(String cause) {
        registry.counter("thaap.risk_context.requests.rejected", "cause", cause).increment();
    }

    public void recordReadinessFailure() {
        registry.counter("thaap.risk_context.readiness.failures", "cause", "KEY_UNAVAILABLE").increment();
    }

    private void recordTransaction(String presence, String transaction) {
        registry.counter("thaap.risk_context.requests", "presence", presence,
                "transaction", transaction).increment();
    }
}
