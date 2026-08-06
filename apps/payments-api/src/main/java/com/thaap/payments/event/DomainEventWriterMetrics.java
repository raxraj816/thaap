package com.thaap.payments.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Foundation-level writer signals for {@code C44-OBS-001}.
 *
 * <p>The publication metrics answer what happened to an event after it was written. Nothing answered whether the write itself succeeded, so a codec or protocol
 * rejection was invisible outside the caller's own failure handling. That gap survived
 * because the row naming this proof closed on a suite nobody wrote ({@code ER-0099},
 * {@code TD-0007}).
 *
 * <p>Dimensions are deliberately limited to the event type, its version and the outcome.
 * Merchant, stream, aggregate, event, actor, request and trace identity are all unbounded
 * and would publish tenant identity into a metrics backend, which the same row forbids.
 */
@Component
public class DomainEventWriterMetrics {

    public static final String METRIC_NAME = "thaap.event.writer";

    private final MeterRegistry meterRegistry;

    public DomainEventWriterMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Reports a durably committed append. Deferred to {@code afterCommit} because a
     * write reported before its transaction commits claims durability the database has
     * not granted; the append participates in the caller's transaction and can still be
     * rolled back by work that follows it.
     */
    public void appendedAfterCommit(DomainEventType eventType, int eventVersion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "A committed domain event write requires active transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        increment(eventType, eventVersion,
                                status == TransactionSynchronization.STATUS_COMMITTED
                                        ? "COMMITTED" : "ROLLED_BACK");
                    }
                });
    }

    /**
     * Reports a rejected append. Recorded immediately rather than after commit: the
     * transaction is about to roll back, so a deferred counter would be indistinguishable
     * from an ordinary rollback and the integrity failure would be lost.
     */
    public void rejected(DomainEventType eventType, int eventVersion) {
        increment(eventType, eventVersion, "REJECTED");
    }

    private void increment(DomainEventType eventType, int eventVersion, String outcome) {
        meterRegistry.counter(METRIC_NAME,
                "event_type", eventType.wireValue(),
                "event_version", Integer.toString(eventVersion),
                "outcome", outcome).increment();
    }
}
