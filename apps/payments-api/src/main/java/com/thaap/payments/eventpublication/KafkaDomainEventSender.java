package com.thaap.payments.eventpublication;

import com.thaap.payments.event.CanonicalDomainEventRecord;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.DomainEventProtocolException;
import com.thaap.payments.event.DomainEventProtocolViolation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "true")
public class KafkaDomainEventSender {

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final CanonicalEventCodec codec;
    private final EventPublicationProperties properties;

    public KafkaDomainEventSender(KafkaTemplate<byte[], byte[]> kafkaTemplate,
            CanonicalEventCodec codec, EventPublicationProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.properties = properties;
    }

    public PublicationSendResult send(PublicationClaim claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Kafka publication must occur outside a database transaction");
        }
        PublicationSendResult validationFailure = validate(claim);
        if (validationFailure != null) {
            return validationFailure;
        }
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(properties.topic(),
                claim.recordKey(), claim.eventRecordBytes());
        record.headers().add(DomainEventKafkaHeaders.EVENT_ID,
                claim.domainEventId().toString().getBytes(StandardCharsets.US_ASCII));
        record.headers().add(DomainEventKafkaHeaders.MERCHANT_ID,
                claim.merchantId().toString().getBytes(StandardCharsets.US_ASCII));
        record.headers().add(DomainEventKafkaHeaders.EVENT_RECORD_SHA256,
                claim.eventRecordSha256().getBytes(StandardCharsets.US_ASCII));
        try {
            SendResult<byte[], byte[]> result = kafkaTemplate.send(record)
                    .get(31, TimeUnit.SECONDS);
            return PublicationSendResult.acknowledged(
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PublicationSendResult.failed(PublicationOutcome.UNKNOWN,
                    PublicationSafeErrorCode.PUBLISHER_INTERRUPTED);
        } catch (TimeoutException exception) {
            return PublicationSendResult.failed(PublicationOutcome.UNKNOWN,
                    PublicationSafeErrorCode.ACKNOWLEDGEMENT_UNKNOWN);
        } catch (ExecutionException exception) {
            return classify(rootCause(exception));
        } catch (RuntimeException exception) {
            return classify(rootCause(exception));
        }
    }

    private PublicationSendResult validate(PublicationClaim claim) {
        byte[] bytes = claim.eventRecordBytes();
        if (bytes.length < 2 || bytes.length > properties.recordMaxBytes()) {
            return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                    PublicationSafeErrorCode.RECORD_TOO_LARGE);
        }
        CanonicalDomainEventRecord event;
        try {
            event = codec.decodeAndValidate(bytes, claim.eventRecordSha256());
        } catch (DomainEventProtocolException exception) {
            return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                    protocolError(exception.violation()));
        }
        if (!event.eventId().equals(claim.domainEventId())
                || !event.merchantId().equals(claim.merchantId())
                || event.streamType() != claim.streamType()
                || !event.streamId().equals(claim.streamId())
                || event.streamVersion() != claim.streamVersion()
                || event.eventVersion() != claim.eventVersion()) {
            return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                    PublicationSafeErrorCode.TENANT_STREAM_DISAGREEMENT);
        }
        return null;
    }

    private PublicationSafeErrorCode protocolError(
            DomainEventProtocolViolation violation) {
        return switch (violation) {
            case PAYLOAD_LIMIT_EXCEEDED -> PublicationSafeErrorCode.RECORD_TOO_LARGE;
            case RECORD_DIGEST_MISMATCH ->
                    PublicationSafeErrorCode.RECORD_DIGEST_MISMATCH;
            case TENANT_STREAM_DISAGREEMENT ->
                    PublicationSafeErrorCode.TENANT_STREAM_DISAGREEMENT;
            default -> PublicationSafeErrorCode.INVALID_EVENT_RECORD;
        };
    }

    private PublicationSendResult classify(Throwable failure) {
        if (failure instanceof org.apache.kafka.common.errors.TimeoutException) {
            return PublicationSendResult.failed(PublicationOutcome.UNKNOWN,
                    PublicationSafeErrorCode.ACKNOWLEDGEMENT_UNKNOWN);
        }
        if (failure instanceof RecordTooLargeException) {
            return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                    PublicationSafeErrorCode.RECORD_TOO_LARGE);
        }
        if (failure instanceof SerializationException) {
            return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                    PublicationSafeErrorCode.SERIALIZATION_REJECTED);
        }
        if (failure instanceof AuthorizationException) {
            return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                    PublicationSafeErrorCode.AUTHORIZATION_REJECTED);
        }
        if (failure instanceof UnknownTopicOrPartitionException) {
            return PublicationSendResult.failed(PublicationOutcome.RETRYABLE_FAILURE,
                    PublicationSafeErrorCode.TOPIC_UNAVAILABLE);
        }
        if (failure instanceof DisconnectException) {
            return PublicationSendResult.failed(PublicationOutcome.RETRYABLE_FAILURE,
                    PublicationSafeErrorCode.BROKER_DISCONNECTED);
        }
        if (failure instanceof NotLeaderOrFollowerException) {
            return PublicationSendResult.failed(PublicationOutcome.RETRYABLE_FAILURE,
                    PublicationSafeErrorCode.LEADER_UNAVAILABLE);
        }
        if (failure instanceof RetriableException) {
            return PublicationSendResult.failed(PublicationOutcome.RETRYABLE_FAILURE,
                    PublicationSafeErrorCode.BROKER_RETRYABLE);
        }
        return PublicationSendResult.failed(PublicationOutcome.PERMANENT_FAILURE,
                PublicationSafeErrorCode.BROKER_REJECTED);
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
