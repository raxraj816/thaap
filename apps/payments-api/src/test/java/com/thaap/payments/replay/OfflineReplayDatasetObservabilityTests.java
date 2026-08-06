package com.thaap.payments.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proof for {@code C51-OBS-001}.
 *
 * <p>These meters have been emitted since the dataset worker was built and nothing asserted them
 * ({@code ER-0099}, {@code TD-0007}). The distinctions an operator depends on are the
 * ones a single "job ran" counter destroys: a first claim against a lease recovered from
 * a dead worker, a recoverable failure against an exhausted retry, and a dispatch refused
 * because the worker is disabled against one refused because the queue is full.
 */
class OfflineReplayDatasetObservabilityTests {

    private static final String DISPATCH = "thaap.risk.replay.datasets.dispatch";
    private static final String LEASES = "thaap.risk.replay.datasets.leases";
    private static final String JOBS = "thaap.risk.replay.datasets.jobs";
    private static final String CHUNKS = "thaap.risk.replay.datasets.chunks";
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("result");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OfflineReplayDatasetMetrics metrics =
            new OfflineReplayDatasetMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void noSignalBecomesVisibleBeforeTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordDispatch("launched");
        metrics.recordLease("claimed");
        metrics.recordJob("completed");
        metrics.recordChunk();

        assertThat(registry.getMeters()).isEmpty();

        commit();

        assertThat(registry.get(DISPATCH).tags("result", "launched").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get(CHUNKS).tags("result", "committed").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void aRecoveredLeaseIsDistinguishableFromAFirstClaim() {
        // Lease recovery is the signal that a worker died mid-dataset. Collapsing it into
        // "claimed" hides exactly the condition an operator is watching for.
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordLease("claimed");
        metrics.recordLease("recovered");
        metrics.recordLease("recovered");
        commit();

        assertThat(registry.get(LEASES).tags("result", "claimed").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get(LEASES).tags("result", "recovered").counter().count())
                .isEqualTo(2.0d);
    }

    @Test
    void everyRefusalReasonAndFailureOutcomeStaysItsOwnSeries() {
        TransactionSynchronizationManager.initSynchronization();

        for (String refusal : new String[] {
                "worker_disabled", "queue_full", "not_claimable", "launch_failed"}) {
            metrics.recordDispatch(refusal);
        }
        for (String outcome : new String[] {
                "batch_completed", "recoverable_failure", "retry_exhausted", "completed",
                "failed_source_drift"}) {
            metrics.recordJob(outcome);
        }
        commit();

        assertThat(registry.find(DISPATCH).counters()).hasSize(4);
        assertThat(registry.find(JOBS).counters()).hasSize(5);
        assertThat(registry.get(JOBS).tags("result", "retry_exhausted").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void everyDatasetMeterCarriesOnlyABoundedNonDisclosingResultTag() {
        // A dataset identifier or merchant identifier as a tag value would make this
        // metric unbounded and would publish tenant identity into the metrics backend.
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordDispatch("launched");
        metrics.recordLease("recovered");
        metrics.recordJob("failed_compatibility_mismatch");
        metrics.recordChunk();
        commit();

        assertThat(registry.getMeters()).isNotEmpty();
        assertThat(registry.getMeters()).allSatisfy(this::assertBoundedAndNonDisclosing);
    }

    @Test
    void aRolledBackDatasetTransactionPublishesNoSignalAtAll() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordJob("completed");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(registry.getMeters()).isEmpty();
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                TransactionSynchronization::afterCommit);
    }

    private void assertBoundedAndNonDisclosing(Meter meter) {
        assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_TAG_KEYS);
        assertThat(meter.getId().getTags()).extracting(Tag::getValue)
                .allSatisfy(value -> assertThat(value).doesNotContain(
                        "merchant-canary", "dataset-canary", "actor-canary",
                        "lease-canary", "feature-canary"));
    }
}
