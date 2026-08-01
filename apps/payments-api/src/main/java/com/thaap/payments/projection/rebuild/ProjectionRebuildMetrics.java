package com.thaap.payments.projection.rebuild;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ProjectionRebuildMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<ProjectionReconciliationFamily, io.micrometer.core.instrument.Counter>
            reconciliationFailures = new EnumMap<>(ProjectionReconciliationFamily.class);

    public ProjectionRebuildMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (ProjectionReconciliationFamily family : ProjectionReconciliationFamily.values()) {
            reconciliationFailures.put(family, meterRegistry.counter(
                    "thaap.projection.rebuild.reconciliation", "family",
                    family.name().toLowerCase(), "outcome", "mismatch"));
        }
    }

    public void afterCommit(String operation, String outcome) {
        Runnable increment = () -> meterRegistry.counter("thaap.projection.rebuild.operations",
                "operation", operation, "outcome", outcome).increment();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        increment.run();
                    }
                });
    }

    public void reconciliationMismatch(ProjectionReconciliationFamily family) {
        reconciliationFailures.get(family).increment();
    }
}
