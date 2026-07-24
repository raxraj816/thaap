package com.thaap.payments.velocity;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class VelocityMetrics {

    private final MeterRegistry registry;

    public VelocityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordLookupAfterCompletion(VelocitySignal signal) {
        Runnable committed = () -> recordLookup(signal, "committed");
        Runnable rolledBack = () -> recordLookup(signal, "rolled_back");
        afterCompletion(committed, rolledBack);
    }

    public void recordCompensation(VelocityDimension dimension, String result) {
        registry.counter("thaap.velocity.compensation", "dimension", tag(dimension),
                "result", result.toLowerCase(Locale.ROOT)).increment();
    }

    private void recordLookup(VelocitySignal signal, String transaction) {
        String cause = signal.failureCause() == null ? "none" : signal.failureCause().name().toLowerCase(Locale.ROOT);
        registry.counter("thaap.velocity.lookups", "dimension", tag(signal.dimension()),
                "status", signal.availability().name().toLowerCase(Locale.ROOT),
                "script_version", signal.scriptVersion(), "cause", cause,
                "transaction", transaction).increment();
    }

    private String tag(VelocityDimension dimension) {
        return dimension.name().toLowerCase(Locale.ROOT);
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
