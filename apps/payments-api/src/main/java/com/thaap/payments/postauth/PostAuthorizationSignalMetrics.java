package com.thaap.payments.postauth;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PostAuthorizationSignalMetrics {
    private final MeterRegistry registry;

    public PostAuthorizationSignalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(PostAuthorizationSignalRecommendation recommendation, String result) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(recommendation, result, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(recommendation, result,
                        status == TransactionSynchronization.STATUS_COMMITTED ? "committed" : "rolled_back");
            }
        });
    }

    private void increment(PostAuthorizationSignalRecommendation recommendation,
            String result, String transaction) {
        registry.counter("thaap.risk.post_authorization.signals",
                "recommendation", recommendation.name(), "result", result,
                "transaction", transaction).increment();
    }
}
