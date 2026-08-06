package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proof for {@code C50-OBS-001}.
 *
 * <p>The row promised that an operator could distinguish every rebuild lifecycle state,
 * lease recovery, progress and duration. The operation and outcome counters shipped with
 * the rebuild control plane; the lease and duration meters did not, and nothing asserted
 * any of them
 * ({@code ER-0099}, {@code TD-0007}). {@link ProjectionRebuildMetrics#lease} and
 * {@link ProjectionRebuildMetrics#jobDuration} were added to close that, because the
 * honest fix for a promise production does not keep is production, not a weaker
 * assertion.
 */
class ProjectionRebuildObservabilityTests {

    private static final String OPERATIONS = "thaap.projection.rebuild.operations";
    private static final String RECONCILIATION = "thaap.projection.rebuild.reconciliation";
    private static final String LEASES = "thaap.projection.rebuild.leases";
    private static final String DURATION = "thaap.projection.rebuild.job.duration";
    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("operation", "outcome", "family", "result");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProjectionRebuildMetrics metrics = new ProjectionRebuildMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void everyLifecycleStateAnOperatorMustDistinguishHasItsOwnSeries() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.afterCommit("start", "queued");
        metrics.afterCommit("dispatch", "launched");
        metrics.afterCommit("job", "completed");
        metrics.afterCommit("reconcile", "passed");
        metrics.afterCommit("reconcile", "mismatch");
        metrics.afterCommit("activate", "activated");
        metrics.afterCommit("rollback", "activated");
        metrics.afterCommit("cancel", "canceled");
        metrics.afterCommit("job", "failed");
        commit();

        assertThat(registry.find(OPERATIONS).counters()).hasSize(9);
        for (String[] pair : new String[][] {
                {"start", "queued"}, {"dispatch", "launched"}, {"job", "completed"},
                {"reconcile", "passed"}, {"reconcile", "mismatch"},
                {"activate", "activated"}, {"rollback", "activated"},
                {"cancel", "canceled"}, {"job", "failed"}}) {
            assertThat(registry.get(OPERATIONS)
                    .tags("operation", pair[0], "outcome", pair[1])
                    .counter().count()).isEqualTo(1.0d);
        }
    }

    @Test
    void noRebuildSignalIsVisibleBeforeTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.afterCommit("activate", "activated");

        assertThat(registry.find(OPERATIONS).counters()).isEmpty();

        commit();

        assertThat(registry.get(OPERATIONS)
                .tags("operation", "activate", "outcome", "activated")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void aRolledBackRebuildOperationIsNeverReportedAsHavingHappened() {
        // Reporting an activation that rolled back would tell an operator the active
        // generation moved when it did not.
        TransactionSynchronizationManager.initSynchronization();

        metrics.afterCommit("activate", "activated");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(registry.find(OPERATIONS).counters()).isEmpty();
    }

    @Test
    void leaseRecoveryIsDistinguishableFromAFreshClaim() {
        metrics.lease("claimed");
        metrics.lease("recovered");

        assertThat(registry.get(LEASES).tags("result", "claimed").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get(LEASES).tags("result", "recovered").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void leaseSignalsDoNotWaitForATransactionThatHasAlreadyCommitted() {
        // The claim commits in its own transaction before the caller's work begins, so a
        // lease meter deferred to the caller's commit would be lost when that work fails
        // — precisely the case an operator most needs to see.
        TransactionSynchronizationManager.initSynchronization();

        metrics.lease("recovered");

        assertThat(registry.get(LEASES).tags("result", "recovered").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void jobDurationIsRecordedSeparatelyForCompletionAndFailure() {
        metrics.jobDuration("completed", Duration.ofSeconds(3));
        metrics.jobDuration("failed", Duration.ofMillis(250));

        assertThat(registry.get(DURATION).tags("outcome", "completed").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get(DURATION).tags("outcome", "completed").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3.0d);
        assertThat(registry.get(DURATION).tags("outcome", "failed").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void reconciliationMismatchIsAttributedToItsFamily() {
        for (ProjectionReconciliationFamily family : ProjectionReconciliationFamily.values()) {
            metrics.reconciliationMismatch(family);
        }

        for (ProjectionReconciliationFamily family : ProjectionReconciliationFamily.values()) {
            assertThat(registry.get(RECONCILIATION)
                    .tags("family", family.name().toLowerCase(), "outcome", "mismatch")
                    .counter().count()).isEqualTo(1.0d);
        }
    }

    @Test
    void noRebuildMeterCarriesTenantGenerationOrActorIdentity() {
        // A rebuild identifier, generation identifier or merchant identifier as a tag
        // would make these series unbounded and publish tenant identity to the backend.
        TransactionSynchronizationManager.initSynchronization();
        metrics.afterCommit("start", "queued");
        commit();
        metrics.lease("recovered");
        metrics.jobDuration("completed", Duration.ofSeconds(1));
        metrics.reconciliationMismatch(ProjectionReconciliationFamily.values()[0]);

        assertThat(registry.getMeters()).isNotEmpty();
        assertThat(registry.getMeters()).allSatisfy(this::assertBoundedAndNonDisclosing);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                TransactionSynchronization::afterCommit);
    }

    private void assertBoundedAndNonDisclosing(Meter meter) {
        assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                .allSatisfy(key -> assertThat(ALLOWED_TAG_KEYS).contains(key));
        assertThat(meter.getId().getTags()).extracting(Tag::getValue)
                .allSatisfy(value -> assertThat(value).doesNotContain(
                        "merchant-canary", "rebuild-canary", "generation-canary",
                        "actor-canary", "checkpoint-canary"));
    }
}
