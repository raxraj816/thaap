package com.thaap.payments.payment;

import com.thaap.payments.event.DomainEventType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records bounded producer outcomes without exposing payment, merchant, request, actor, or
 * payload identifiers. A successful append is not observable as committed until the owning
 * transaction actually completes.
 */
@Component
public class PaymentDomainEventMetrics {

    static final String METRIC_NAME = "thaap.domain.event.producer.outcomes";
    static final String PRODUCER_FAMILY = "PAYMENTS_CORE";

    private final MeterRegistry registry;

    public PaymentDomainEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAfterCompletion(DomainEventType eventType, int eventVersion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Payment domain-event outcome requires active transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String outcome = status == TransactionSynchronization.STATUS_COMMITTED
                        ? ProducerOutcome.COMMITTED.name()
                        : ProducerOutcome.ROLLED_BACK.name();
                increment(eventType, eventVersion, outcome);
            }
        });
    }

    public void recordWriteFailure(DomainEventType eventType, int eventVersion) {
        increment(eventType, eventVersion, ProducerOutcome.WRITE_FAILED.name());
    }

    private void increment(DomainEventType eventType, int eventVersion, String outcome) {
        registry.counter(METRIC_NAME,
                "producer_family", eventType.producer().name(),
                "event_type", eventType.wireValue(),
                "event_version", Integer.toString(eventVersion),
                "outcome", outcome).increment();
    }

    private enum ProducerOutcome {
        COMMITTED,
        ROLLED_BACK,
        WRITE_FAILED
    }
}
