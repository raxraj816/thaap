package com.thaap.payments.projection;

import com.thaap.payments.eventpublication.DomainEventKafkaHeaders;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "thaap.projection-consumer.enabled", havingValue = "true")
public class PaymentRiskProjectionKafkaListener {

    private final PaymentRiskProjectionConsumerService consumerService;
    private final PaymentRiskProjectionMetrics metrics;

    public PaymentRiskProjectionKafkaListener(PaymentRiskProjectionConsumerService consumerService,
            PaymentRiskProjectionMetrics metrics) {
        this.consumerService = consumerService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${thaap.event-publication.topic}",
            groupId = ProjectionNames.PAYMENT_RISK_CONSUMER)
    public void receive(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        TransportedDomainEvent transported = new TransportedDomainEvent(record.topic(),
                record.partition(), record.offset(), record.key(), record.value(),
                optionalUuidHeader(record, DomainEventKafkaHeaders.EVENT_ID),
                optionalUuidHeader(record, DomainEventKafkaHeaders.MERCHANT_ID),
                optionalTextHeader(record, DomainEventKafkaHeaders.EVENT_RECORD_SHA256, 64));
        long startedAt = System.nanoTime();
        try {
            ProjectionProcessingOutcome outcome = consumerService.process(transported);
            metrics.record(outcome, System.nanoTime() - startedAt);
            acknowledgment.acknowledge();
        } catch (RuntimeException exception) {
            metrics.recordFailure(classify(exception), System.nanoTime() - startedAt);
            throw exception;
        }
    }

    private UUID optionalUuidHeader(ConsumerRecord<byte[], byte[]> record, String name) {
        try {
            String value = optionalTextHeader(record, name, 36);
            return value == null ? null : UUID.fromString(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String optionalTextHeader(ConsumerRecord<byte[], byte[]> record, String name,
            int expectedLength) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length != expectedLength) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.US_ASCII);
        if (!java.util.Arrays.equals(value.getBytes(StandardCharsets.US_ASCII), header.value())) {
            return null;
        }
        return value;
    }

    private ProjectionConsumerFailure classify(RuntimeException exception) {
        if (exception instanceof ProjectionSourceUnavailableException) {
            return ProjectionConsumerFailure.SOURCE_UNAVAILABLE;
        }
        if (exception instanceof org.springframework.dao.DataAccessException) {
            return ProjectionConsumerFailure.DATABASE_UNAVAILABLE;
        }
        return ProjectionConsumerFailure.INTERNAL_FAILURE;
    }
}
