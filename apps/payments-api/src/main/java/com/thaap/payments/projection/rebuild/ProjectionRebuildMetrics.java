package com.thaap.payments.projection.rebuild;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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

    /**
     * Records whether a rebuild lease was claimed fresh or taken over from a worker whose
     * lease expired. A recovered lease is the signal that a worker died mid-rebuild, and
     * collapsing it into the dispatch counter hides exactly that condition.
     *
     * <p>Emitted outside the transaction synchronisation used by {@link #afterCommit},
     * because the claim commits in its own transaction before the caller's work begins.
     */
    public void lease(String result) {
        meterRegistry.counter("thaap.projection.rebuild.leases", "result", result)
                .increment();
    }

    /**
     * Records how long a rebuild job ran. Without a duration an operator can see that a
     * rebuild completed but not that it is taking progressively longer, which is the
     * shape a degrading rebuild has before it starts failing.
     */
    public void jobDuration(String outcome, Duration duration) {
        Timer.builder("thaap.projection.rebuild.job.duration")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }
}
