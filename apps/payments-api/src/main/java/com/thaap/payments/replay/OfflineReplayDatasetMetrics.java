package com.thaap.payments.replay;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OfflineReplayDatasetMetrics {

    private final MeterRegistry registry;

    public OfflineReplayDatasetMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDispatch(String result) {
        incrementAfterCommit("thaap.risk.replay.datasets.dispatch", "result", result);
    }

    public void recordLease(String result) {
        incrementAfterCommit("thaap.risk.replay.datasets.leases", "result", result);
    }

    public void recordJob(String result) {
        incrementAfterCommit("thaap.risk.replay.datasets.jobs", "result", result);
    }

    public void recordChunk() {
        incrementAfterCommit("thaap.risk.replay.datasets.chunks", "result", "committed");
    }

    private void incrementAfterCommit(String name, String key, String value) {
        Runnable increment = () -> registry.counter(name, key, value).increment();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                increment.run();
            }
        });
    }
}
