package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.event.DomainEventType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentEventObservabilityTests {

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "producer_family", "event_type", "event_version", "outcome");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentDomainEventMetrics metrics = new PaymentDomainEventMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void successfulWritesBecomeVisibleOnlyAfterCommitForEveryPaymentEventType() {
        List<DomainEventType> eventTypes = List.of(
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventType.PAYMENT_INTENT_CANCELED,
                DomainEventType.FRAUD_DECISION_CREATED,
                DomainEventType.PAYMENT_ATTEMPT_COMPLETED,
                DomainEventType.AUTHORIZATION_CREATED,
                DomainEventType.AUTHORIZATION_VOIDED,
                DomainEventType.CAPTURE_CREATED,
                DomainEventType.REFUND_CREATED,
                DomainEventType.CHARGEBACK_CREATED,
                DomainEventType.AUTHENTICATION_CHALLENGE_CREATED,
                DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED);
        TransactionSynchronizationManager.initSynchronization();

        eventTypes.forEach(eventType -> metrics.recordAfterCompletion(eventType, 1));

        assertThat(registry.find(PaymentDomainEventMetrics.METRIC_NAME).counters()).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_COMMITTED));

        for (DomainEventType eventType : eventTypes) {
            assertThat(registry.get(PaymentDomainEventMetrics.METRIC_NAME)
                    .tags("producer_family", PaymentDomainEventMetrics.PRODUCER_FAMILY,
                            "event_type", eventType.wireValue(),
                            "event_version", "1", "outcome", "COMMITTED")
                    .counter().count()).isEqualTo(1.0d);
        }
    }

    @Test
    void rollbackAndWriteFailureAreDistinctAndAllDimensionsStayClosed() {
        TransactionSynchronizationManager.initSynchronization();
        metrics.recordAfterCompletion(DomainEventType.AUTHORIZATION_VOIDED, 1);
        metrics.recordWriteFailure(DomainEventType.PAYMENT_INTENT_CANCELED, 1);

        assertThat(registry.get(PaymentDomainEventMetrics.METRIC_NAME)
                .tags("producer_family", PaymentDomainEventMetrics.PRODUCER_FAMILY,
                        "event_type", DomainEventType.PAYMENT_INTENT_CANCELED.wireValue(),
                        "event_version", "1", "outcome", "WRITE_FAILED")
                .counter().count()).isEqualTo(1.0d);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(registry.get(PaymentDomainEventMetrics.METRIC_NAME)
                .tags("producer_family", PaymentDomainEventMetrics.PRODUCER_FAMILY,
                        "event_type", DomainEventType.AUTHORIZATION_VOIDED.wireValue(),
                        "event_version", "1", "outcome", "ROLLED_BACK")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters()).allSatisfy(this::assertBoundedTags);
    }

    @Test
    void successfulWriteCannotBeReportedWithoutTransactionTruth() {
        assertThatThrownBy(() -> metrics.recordAfterCompletion(
                DomainEventType.PAYMENT_INTENT_CREATED, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction synchronization");
        assertThat(registry.getMeters()).isEmpty();
    }

    private void assertBoundedTags(Meter meter) {
        assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_TAG_KEYS);
        assertThat(meter.getId().getTags()).extracting(Tag::getValue)
                .allSatisfy(value -> assertThat(value).doesNotContain(
                        "merchant-canary", "payment-canary", "event-canary",
                        "actor-canary", "request-canary", "trace-canary",
                        "credential-canary", "processor-canary"));
    }
}
