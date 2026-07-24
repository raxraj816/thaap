package com.thaap.payments.fraudlist;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class FraudListMetrics {

    private final MeterRegistry registry;

    public FraudListMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordEvaluation(FraudListEvaluation evaluation) {
        afterCompletion(
                () -> recordEvaluation(evaluation, "committed"),
                () -> recordEvaluation(evaluation, "rolled_back"));
    }

    public void recordEvaluationLatency(long elapsedNanos) {
        afterCompletion(
                () -> recordEvaluationLatency(elapsedNanos, "committed"),
                () -> recordEvaluationLatency(elapsedNanos, "rolled_back"));
    }

    public void recordManagement(String operation) {
        afterCompletion(
                () -> recordManagement(operation, "committed"),
                () -> recordManagement(operation, "rolled_back"));
    }

    private void recordEvaluation(FraudListEvaluation evaluation, String transaction) {
        registry.counter("thaap.fraud.list.evaluations",
                "identity_type", evaluation.identityType().name(),
                "result", evaluation.matched() ? "MATCH" : "NO_MATCH",
                "transaction", transaction).increment();
    }

    private void recordEvaluationLatency(long elapsedNanos, String transaction) {
        registry.timer("thaap.fraud.list.evaluation.latency", "transaction", transaction)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private void recordManagement(String operation, String transaction) {
        registry.counter("thaap.fraud.list.management", "operation", operation,
                "transaction", transaction).increment();
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
