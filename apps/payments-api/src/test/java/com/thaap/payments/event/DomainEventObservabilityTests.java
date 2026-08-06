package com.thaap.payments.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proof for {@code C44-OBS-001}.
 *
 * <p>The foundation had no writer signal at all until {@link DomainEventWriterMetrics}
 * was added alongside this suite. The row had been closed for a long time naming a suite
 * nobody wrote ({@code ER-0099}, {@code TD-0007}); the pending-count and oldest-age half
 * of its promise is held by the event publication meters, and the writer-outcome and
 * integrity-failure half was simply absent.
 */
class DomainEventObservabilityTests {

    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("event_type", "event_version", "outcome");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DomainEventWriterMetrics metrics = new DomainEventWriterMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aCommittedAppendIsInvisibleUntilTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.appendedAfterCommit(DomainEventType.PAYMENT_INTENT_CREATED, 1);

        assertThat(registry.find(DomainEventWriterMetrics.METRIC_NAME).counters()).isEmpty();

        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(registry.get(DomainEventWriterMetrics.METRIC_NAME)
                .tags("event_type", DomainEventType.PAYMENT_INTENT_CREATED.wireValue(),
                        "event_version", "1", "outcome", "COMMITTED")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void aRolledBackAppendIsReportedAsRolledBackRatherThanAsCommitted() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.appendedAfterCommit(DomainEventType.CAPTURE_CREATED, 1);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(registry.get(DomainEventWriterMetrics.METRIC_NAME)
                .tags("event_type", DomainEventType.CAPTURE_CREATED.wireValue(),
                        "event_version", "1", "outcome", "ROLLED_BACK")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.find(DomainEventWriterMetrics.METRIC_NAME)
                .tags("outcome", "COMMITTED").counters()).isEmpty();
    }

    @Test
    void anIntegrityRejectionIsCountedImmediatelyRatherThanAfterCommit() {
        // The rejecting transaction is about to roll back. A deferred counter would make
        // an integrity failure indistinguishable from an ordinary rollback, which is the
        // one distinction this signal exists to make.
        TransactionSynchronizationManager.initSynchronization();

        metrics.rejected(DomainEventType.FRAUD_DECISION_CREATED, 1);

        assertThat(registry.get(DomainEventWriterMetrics.METRIC_NAME)
                .tags("event_type", DomainEventType.FRAUD_DECISION_CREATED.wireValue(),
                        "event_version", "1", "outcome", "REJECTED")
                .counter().count()).isEqualTo(1.0d);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(registry.get(DomainEventWriterMetrics.METRIC_NAME)
                .tags("outcome", "REJECTED").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void aCommittedAppendCannotBeClaimedWithoutTransactionTruth() {
        assertThatThrownBy(() -> metrics.appendedAfterCommit(
                DomainEventType.REFUND_CREATED, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction synchronization");
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void everyCatalogueEventTypeReportsUnderOneBoundedNonDisclosingTagSet() {
        TransactionSynchronizationManager.initSynchronization();
        for (DomainEventType eventType : DomainEventType.values()) {
            metrics.appendedAfterCommit(eventType, 1);
        }
        complete(TransactionSynchronization.STATUS_COMMITTED);
        metrics.rejected(DomainEventType.values()[0], 1);

        assertThat(registry.find(DomainEventWriterMetrics.METRIC_NAME).counters())
                .hasSize(DomainEventType.values().length + 1);
        assertThat(registry.getMeters()).allSatisfy(this::assertBoundedAndNonDisclosing);
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(status));
    }

    private void assertBoundedAndNonDisclosing(Meter meter) {
        assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_TAG_KEYS);
        assertThat(meter.getId().getTags()).extracting(Tag::getValue)
                .allSatisfy(value -> assertThat(value).doesNotContain(
                        "merchant-canary", "stream-canary", "aggregate-canary",
                        "actor-canary", "request-canary", "trace-canary",
                        "payload-canary", "digest-canary", "credential-canary"));
    }
}
