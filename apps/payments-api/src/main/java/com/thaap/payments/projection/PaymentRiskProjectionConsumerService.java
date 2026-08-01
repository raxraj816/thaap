package com.thaap.payments.projection;

import com.thaap.payments.event.CanonicalDomainEventRecord;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.DomainEvent;
import com.thaap.payments.event.DomainEventProtocolException;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.persistence.JdbcTime;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentRiskProjectionConsumerService {

    public static final String TOPIC_NAME = "thaap.domain-events.v1";

    private final CanonicalEventCodec codec;
    private final DomainEventRepository eventRepository;
    private final ProjectionDefinitionStore definitionStore;
    private final PaymentRiskProjectionHandler handler;
    private final PaymentRiskProjectionMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PaymentRiskProjectionConsumerService(CanonicalEventCodec codec,
            DomainEventRepository eventRepository, ProjectionDefinitionStore definitionStore,
            PaymentRiskProjectionHandler handler, PaymentRiskProjectionMetrics metrics,
            JdbcTemplate jdbcTemplate, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.codec = codec;
        this.eventRepository = eventRepository;
        this.definitionStore = definitionStore;
        this.handler = handler;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ProjectionProcessingOutcome process(TransportedDomainEvent transported) {
        requireTransportShape(transported);
        String actualRecordDigest = recordDigest(transported.eventRecordBytes());
        CanonicalDomainEventRecord event;
        try {
            event = codec.decodeAndValidate(transported.eventRecordBytes(),
                    actualRecordDigest);
        } catch (DomainEventProtocolException exception) {
            return quarantineTransport(transported, actualRecordDigest,
                    ConsumerQuarantineReason.fromProtocol(exception.violation()));
        }
        DomainEvent source = eventRepository.findById(event.eventId())
                .orElseThrow(ProjectionSourceUnavailableException::new);
        if (!recordMatchesImmutableSource(event, source)) {
            return quarantineTransport(transported, actualRecordDigest,
                    ConsumerQuarantineReason.SOURCE_IDENTITY_CONFLICT);
        }
        ConsumerQuarantineReason transportReason = transportMetadataFailure(
                event, transported, source);
        if (transportReason != null) {
            return quarantineTransport(transported, actualRecordDigest, transportReason);
        }
        try {
            ProjectionProcessingOutcome result = transaction.execute(status ->
                    applyValidated(event, transported));
            if (result == null) {
                throw new IllegalStateException("Projection transaction returned no outcome");
            }
            return result;
        } catch (QuarantinableProjectionException exception) {
            return quarantine(source, transported, exception.reason());
        } catch (ProjectionInvariantException exception) {
            return quarantine(source, transported,
                    ConsumerQuarantineReason.PROJECTION_INVARIANT_FAILURE);
        } catch (DataIntegrityViolationException exception) {
            return quarantine(source, transported,
                    ConsumerQuarantineReason.PROJECTION_INVARIANT_FAILURE);
        }
    }

    public int recoverFromImmutableSource(UUID quarantineId, UUID operatorId) {
        if (quarantineId == null || operatorId == null) {
            throw new IllegalArgumentException("Quarantine and operator identities are required");
        }
        Integer recovered = transaction.execute(status -> {
            ActiveProjectionGeneration paymentGeneration = definitionStore
                    .requireActiveForUpdate(ProjectionNames.PAYMENT_RISK);
            ActiveProjectionGeneration merchantGeneration = definitionStore
                    .requireActiveForUpdate(ProjectionNames.MERCHANT_RISK);
            QuarantineState initial = findQuarantine(quarantineId);
            if (initial == null || !"OPEN".equals(initial.lifecycleStatus())) {
                throw new IllegalStateException("Quarantine is not ready for source recovery");
            }
            int count = recoverOne(initial, operatorId, paymentGeneration,
                    merchantGeneration);
            long nextVersion = initial.streamVersion() + 1;
            while (count < 100) {
                QuarantineState successor = findHeldSuccessor(initial.merchantId(),
                        initial.streamType(), initial.streamId(), nextVersion);
                if (successor == null) {
                    break;
                }
                count += recoverOne(successor, operatorId, paymentGeneration,
                        merchantGeneration);
                nextVersion++;
            }
            return count;
        });
        if (recovered == null) {
            throw new IllegalStateException("Quarantine recovery returned no result");
        }
        return recovered;
    }

    private int recoverOne(QuarantineState quarantine, UUID operatorId,
            ActiveProjectionGeneration paymentGeneration,
            ActiveProjectionGeneration merchantGeneration) {
        lockSourceEvent(quarantine.merchantId(), quarantine.domainEventId());
        DomainEvent source = eventRepository.findByMerchantIdAndId(
                        quarantine.merchantId(), quarantine.domainEventId())
                .orElseThrow(ProjectionSourceUnavailableException::new);
        CanonicalDomainEventRecord event = codec.decodeAndValidate(
                source.getEventRecordBytes(), source.getEventRecordSha256());
        InboxState inbox = findInboxForUpdate(event.eventId());
        if (inbox == null || !constantTimeEquals(inbox.recordSha256(),
                source.getEventRecordSha256())
                || !("QUARANTINED".equals(inbox.lifecycleStatus())
                    || "HELD".equals(inbox.lifecycleStatus()))) {
            throw new IllegalStateException("Quarantine lost recoverable inbox lineage");
        }
        QuarantineState lockedQuarantine = findQuarantineForUpdate(
                quarantine.quarantineId());
        if (!sameQuarantineIdentity(quarantine, lockedQuarantine)
                || !"OPEN".equals(lockedQuarantine.lifecycleStatus())) {
            throw new IllegalStateException("Quarantine recovery lost its lifecycle fence");
        }
        Instant processedAt = safeProcessedAt(event.recordedAt());
        int requested = jdbcTemplate.update("""
                UPDATE consumer_quarantine
                SET lifecycle_status = 'RETRY_REQUESTED', updated_at = ?
                WHERE id = ? AND lifecycle_status = 'OPEN'
                """, timestamp(processedAt), quarantine.quarantineId());
        if (requested != 1) {
            throw new IllegalStateException("Quarantine retry lost its lifecycle fence");
        }
        assertExpectedStreamVersion(event, paymentGeneration.generationId());
        assertExpectedStreamVersion(event, merchantGeneration.generationId());
        handler.apply(event, paymentGeneration, merchantGeneration, processedAt);
        advanceStreamPosition(event, paymentGeneration.generationId(), processedAt);
        advanceStreamPosition(event, merchantGeneration.generationId(), processedAt);
        TransportedDomainEvent transported = new TransportedDomainEvent(
                lockedQuarantine.topicName(), lockedQuarantine.brokerPartition(),
                lockedQuarantine.brokerOffset(), TenantQualifiedStreamKey.encode(
                        event.merchantId(), event.streamType(), event.streamId()),
                event.eventRecordBytes(), event.eventId(), event.merchantId(),
                event.eventRecordSha256());
        advanceCheckpoint(event, transported, paymentGeneration.generationId(), processedAt);
        advanceCheckpoint(event, transported, merchantGeneration.generationId(), processedAt);
        jdbcTemplate.update("""
                UPDATE consumer_inbox
                SET lifecycle_status = 'APPLIED', completed_at = ?
                WHERE consumer_name = ? AND domain_event_id = ?
                  AND lifecycle_status IN ('QUARANTINED', 'HELD')
                """, timestamp(processedAt), ProjectionNames.PAYMENT_RISK_CONSUMER,
                event.eventId());
        int resolved = jdbcTemplate.update("""
                UPDATE consumer_quarantine
                SET lifecycle_status = 'RESOLVED', resolved_by_operator_id = ?,
                    updated_at = ?, resolved_at = ?
                WHERE id = ? AND lifecycle_status = 'RETRY_REQUESTED'
                """, operatorId, timestamp(processedAt), timestamp(processedAt),
                quarantine.quarantineId());
        if (resolved != 1) {
            throw new IllegalStateException("Quarantine resolution lost its lifecycle fence");
        }
        return 1;
    }

    private ProjectionProcessingOutcome applyValidated(CanonicalDomainEventRecord event,
            TransportedDomainEvent transported) {
        ActiveProjectionGeneration paymentGeneration = definitionStore.requireActiveForUpdate(
                ProjectionNames.PAYMENT_RISK);
        ActiveProjectionGeneration merchantGeneration = definitionStore.requireActiveForUpdate(
                ProjectionNames.MERCHANT_RISK);
        lockSourceEvent(event.merchantId(), event.eventId());
        InboxState inbox = findInboxForUpdate(event.eventId());
        if (inbox != null) {
            if (!constantTimeEquals(inbox.recordSha256(), event.eventRecordSha256())) {
                throw new IllegalStateException("Consumer inbox event identity was reused");
            }
            return ProjectionProcessingOutcome.DUPLICATE;
        }
        assertExpectedStreamVersion(event, paymentGeneration.generationId());
        assertExpectedStreamVersion(event, merchantGeneration.generationId());
        Instant processedAt = safeProcessedAt(event.recordedAt());
        handler.apply(event, paymentGeneration, merchantGeneration, processedAt);
        advanceStreamPosition(event, paymentGeneration.generationId(), processedAt);
        advanceStreamPosition(event, merchantGeneration.generationId(), processedAt);
        advanceCheckpoint(event, transported, paymentGeneration.generationId(), processedAt);
        advanceCheckpoint(event, transported, merchantGeneration.generationId(), processedAt);
        insertInbox(event.merchantId(), event.eventId(), event.eventRecordSha256(),
                paymentGeneration.generationId(), "APPLIED", transported, processedAt);
        return ProjectionProcessingOutcome.APPLIED;
    }

    private ProjectionProcessingOutcome quarantine(DomainEvent source,
            TransportedDomainEvent transported, ConsumerQuarantineReason reason) {
        ProjectionProcessingOutcome result = transaction.execute(status -> {
            ActiveProjectionGeneration paymentGeneration = definitionStore.requireActiveForUpdate(
                    ProjectionNames.PAYMENT_RISK);
            definitionStore.requireActiveForUpdate(ProjectionNames.MERCHANT_RISK);
            lockSourceEvent(source.getMerchantId(), source.getId());
            InboxState existing = findInboxForUpdate(source.getId());
            if (existing != null) {
                if (!constantTimeEquals(existing.recordSha256(),
                        source.getEventRecordSha256())) {
                    throw new IllegalStateException("Consumer inbox digest conflict");
                }
                return ProjectionProcessingOutcome.DUPLICATE;
            }
            Instant recordedAt = source.getRecordedAt();
            Instant processedAt = safeProcessedAt(recordedAt);
            boolean held = reason == ConsumerQuarantineReason.MISSING_PREDECESSOR
                    || reason == ConsumerQuarantineReason.PREDECESSOR_UNAVAILABLE;
            String inboxStatus = held ? "HELD" : "QUARANTINED";
            insertInbox(source.getMerchantId(), source.getId(),
                    source.getEventRecordSha256(), paymentGeneration.generationId(), inboxStatus,
                    transported, processedAt);
            jdbcTemplate.update("""
                    INSERT INTO consumer_quarantine (
                        id, merchant_id, consumer_name, domain_event_id,
                        source_domain_event_id, event_record_sha256, reason_code,
                        lifecycle_status, compatibility_adapter_version,
                        resolved_by_operator_id, created_at, updated_at, resolved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', NULL, NULL, ?, ?, NULL)
                    """, UUID.randomUUID(), source.getMerchantId(),
                    ProjectionNames.PAYMENT_RISK_CONSUMER, source.getId(), source.getId(),
                    source.getEventRecordSha256(), reason.name(), timestamp(processedAt),
                    timestamp(processedAt));
            return held ? ProjectionProcessingOutcome.HELD
                    : ProjectionProcessingOutcome.QUARANTINED;
        });
        if (result == null) {
            throw new IllegalStateException("Quarantine transaction returned no outcome");
        }
        if (result != ProjectionProcessingOutcome.DUPLICATE) {
            metrics.recordQuarantine(reason);
        }
        return result;
    }

    private ProjectionProcessingOutcome quarantineTransport(
            TransportedDomainEvent transported, String actualRecordDigest,
            ConsumerQuarantineReason reason) {
        try {
            ProjectionProcessingOutcome result = transaction.execute(status -> {
                TransportQuarantineState existing = findTransportQuarantineForUpdate(
                        transported);
                if (existing != null) {
                    return existing.matches(actualRecordDigest,
                            transported.eventRecordBytes() == null
                                    ? 0 : transported.eventRecordBytes().length,
                            reason)
                            ? ProjectionProcessingOutcome.DUPLICATE
                            : failTransportPositionConflict();
                }
                jdbcTemplate.update("""
                        INSERT INTO consumer_transport_quarantine (
                            id, consumer_name, topic_name, broker_partition,
                            broker_offset, received_record_sha256,
                            received_record_size_bytes, reason_code,
                            lifecycle_status, recoverable, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', FALSE, ?)
                        """, UUID.randomUUID(), ProjectionNames.PAYMENT_RISK_CONSUMER,
                        transported.topic(), transported.partition(), transported.offset(),
                        actualRecordDigest,
                        transported.eventRecordBytes() == null
                                ? 0L : (long) transported.eventRecordBytes().length,
                        reason.name(), timestamp(clock.instant()));
                return ProjectionProcessingOutcome.QUARANTINED;
            });
            return recordTransportQuarantineMetric(result, reason);
        } catch (DuplicateKeyException exception) {
            ProjectionProcessingOutcome result = transaction.execute(status -> {
                TransportQuarantineState existing = findTransportQuarantineForUpdate(
                        transported);
                if (existing == null || !existing.matches(actualRecordDigest,
                        transported.eventRecordBytes() == null
                                ? 0 : transported.eventRecordBytes().length,
                        reason)) {
                    throw new IllegalStateException(
                            "Broker position was reused with conflicting transport evidence",
                            exception);
                }
                return ProjectionProcessingOutcome.DUPLICATE;
            });
            return recordTransportQuarantineMetric(result, reason);
        }
    }

    private ProjectionProcessingOutcome recordTransportQuarantineMetric(
            ProjectionProcessingOutcome result, ConsumerQuarantineReason reason) {
        if (result == null) {
            throw new IllegalStateException("Transport quarantine transaction returned no outcome");
        }
        if (result != ProjectionProcessingOutcome.DUPLICATE) {
            metrics.recordQuarantine(reason);
        }
        return result;
    }

    private ProjectionProcessingOutcome failTransportPositionConflict() {
        throw new IllegalStateException(
                "Broker position was reused with conflicting transport evidence");
    }

    private void requireTransportShape(TransportedDomainEvent transported) {
        if (transported == null || !TOPIC_NAME.equals(transported.topic())
                || transported.partition() < 0 || transported.partition() >= 6
                || transported.offset() < 0) {
            throw new IllegalArgumentException("Transported domain event metadata is invalid");
        }
    }

    private boolean recordMatchesImmutableSource(CanonicalDomainEventRecord event,
            DomainEvent source) {
        return event.eventId().equals(source.getId())
                && event.merchantId().equals(source.getMerchantId())
                && event.streamType() == source.getStreamType()
                && event.streamId().equals(source.getStreamId())
                && event.streamVersion() == source.getStreamVersion()
                && event.eventType() == source.getEventType()
                && event.eventVersion() == source.getEventVersion()
                && constantTimeEquals(event.eventRecordSha256(),
                        source.getEventRecordSha256())
                && MessageDigest.isEqual(event.eventRecordBytes(),
                        source.getEventRecordBytes());
    }

    private ConsumerQuarantineReason transportMetadataFailure(
            CanonicalDomainEventRecord event, TransportedDomainEvent transported,
            DomainEvent source) {
        if (transported.sourceEventId() == null
                || !event.eventId().equals(transported.sourceEventId())) {
            return ConsumerQuarantineReason.SOURCE_IDENTITY_CONFLICT;
        }
        if (transported.sourceMerchantId() == null
                || !source.getMerchantId().equals(transported.sourceMerchantId())) {
            return ConsumerQuarantineReason.TENANT_STREAM_DISAGREEMENT;
        }
        if (!constantTimeEquals(transported.eventRecordSha256(),
                source.getEventRecordSha256())) {
            return ConsumerQuarantineReason.RECORD_DIGEST_MISMATCH;
        }
        if (!Arrays.equals(transported.recordKey(), TenantQualifiedStreamKey.encode(
                event.merchantId(), event.streamType(), event.streamId()))) {
            return ConsumerQuarantineReason.RECORD_KEY_MISMATCH;
        }
        return null;
    }

    private String recordDigest(byte[] recordBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    recordBytes == null ? new byte[0] : recordBytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private TransportQuarantineState findTransportQuarantineForUpdate(
            TransportedDomainEvent transported) {
        List<TransportQuarantineState> rows = jdbcTemplate.query("""
                SELECT received_record_sha256, received_record_size_bytes, reason_code
                FROM consumer_transport_quarantine
                WHERE consumer_name = ? AND topic_name = ?
                  AND broker_partition = ? AND broker_offset = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new TransportQuarantineState(
                        resultSet.getString(1), resultSet.getLong(2),
                        ConsumerQuarantineReason.valueOf(resultSet.getString(3))),
                ProjectionNames.PAYMENT_RISK_CONSUMER, transported.topic(),
                transported.partition(), transported.offset());
        if (rows.size() > 1) {
            throw new IllegalStateException("Broker position quarantine identity is ambiguous");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void lockSourceEvent(UUID merchantId, UUID eventId) {
        List<UUID> rows = jdbcTemplate.query("""
                SELECT id FROM domain_events
                WHERE merchant_id = ? AND id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                merchantId, eventId);
        if (rows.size() != 1) {
            throw new ProjectionSourceUnavailableException();
        }
    }

    private InboxState findInboxForUpdate(UUID eventId) {
        List<InboxState> rows = jdbcTemplate.query("""
                SELECT event_record_sha256, lifecycle_status
                FROM consumer_inbox
                WHERE consumer_name = ? AND domain_event_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new InboxState(
                        resultSet.getString(1), resultSet.getString(2)),
                ProjectionNames.PAYMENT_RISK_CONSUMER, eventId);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Consumer inbox identity is ambiguous");
        }
        return rows.getFirst();
    }

    private void assertExpectedStreamVersion(CanonicalDomainEventRecord event,
            UUID generationId) {
        List<Long> versions = jdbcTemplate.query("""
                SELECT last_stream_version
                FROM projection_stream_positions
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND stream_type = ? AND stream_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getLong(1), event.merchantId(),
                generationId, event.streamType().name(), event.streamId());
        long expected = versions.isEmpty() ? 1 : Math.addExact(versions.getFirst(), 1);
        if (event.streamVersion() < expected) {
            throw new QuarantinableProjectionException(
                    ConsumerQuarantineReason.IMPOSSIBLE_STREAM_VERSION);
        }
        if (event.streamVersion() > expected) {
            throw new QuarantinableProjectionException(hasUnavailablePredecessor(
                    event.merchantId(), event.streamType().name(), event.streamId(), expected)
                    ? ConsumerQuarantineReason.PREDECESSOR_UNAVAILABLE
                    : ConsumerQuarantineReason.MISSING_PREDECESSOR);
        }
    }

    private boolean hasUnavailablePredecessor(UUID merchantId, String streamType,
            UUID streamId, long expectedVersion) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM consumer_quarantine quarantine
                JOIN domain_events event
                  ON event.merchant_id = quarantine.merchant_id
                 AND event.id = quarantine.domain_event_id
                WHERE quarantine.merchant_id = ?
                  AND quarantine.consumer_name = ?
                  AND event.stream_type = ? AND event.stream_id = ?
                  AND event.stream_version = ?
                  AND quarantine.lifecycle_status IN ('OPEN', 'RETRY_REQUESTED')
                """, Integer.class, merchantId, ProjectionNames.PAYMENT_RISK_CONSUMER,
                streamType, streamId, expectedVersion);
        return count != null && count > 0;
    }

    private QuarantineState findQuarantine(UUID quarantineId) {
        List<QuarantineState> rows = jdbcTemplate.query("""
                SELECT quarantine.id, quarantine.merchant_id,
                       quarantine.domain_event_id, quarantine.lifecycle_status,
                       inbox.topic_name, inbox.broker_partition, inbox.broker_offset,
                       event.stream_type, event.stream_id, event.stream_version,
                       event.recorded_at
                FROM consumer_quarantine quarantine
                JOIN consumer_inbox inbox
                  ON inbox.merchant_id = quarantine.merchant_id
                 AND inbox.consumer_name = quarantine.consumer_name
                 AND inbox.domain_event_id = quarantine.domain_event_id
                JOIN domain_events event
                  ON event.merchant_id = quarantine.merchant_id
                 AND event.id = quarantine.domain_event_id
                WHERE quarantine.id = ?
                """, (resultSet, rowNumber) -> new QuarantineState(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getString(4),
                        resultSet.getString(5), resultSet.getInt(6), resultSet.getLong(7),
                        resultSet.getString(8), resultSet.getObject(9, UUID.class),
                        resultSet.getLong(10), JdbcTime.instant(resultSet, 11)),
                quarantineId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private QuarantineState findQuarantineForUpdate(UUID quarantineId) {
        List<QuarantineState> rows = jdbcTemplate.query("""
                SELECT quarantine.id, quarantine.merchant_id,
                       quarantine.domain_event_id, quarantine.lifecycle_status,
                       inbox.topic_name, inbox.broker_partition, inbox.broker_offset,
                       event.stream_type, event.stream_id, event.stream_version,
                       event.recorded_at
                FROM consumer_quarantine quarantine
                JOIN consumer_inbox inbox
                  ON inbox.merchant_id = quarantine.merchant_id
                 AND inbox.consumer_name = quarantine.consumer_name
                 AND inbox.domain_event_id = quarantine.domain_event_id
                JOIN domain_events event
                  ON event.merchant_id = quarantine.merchant_id
                 AND event.id = quarantine.domain_event_id
                WHERE quarantine.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new QuarantineState(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getString(4),
                        resultSet.getString(5), resultSet.getInt(6), resultSet.getLong(7),
                        resultSet.getString(8), resultSet.getObject(9, UUID.class),
                        resultSet.getLong(10), JdbcTime.instant(resultSet, 11)),
                quarantineId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private QuarantineState findHeldSuccessor(UUID merchantId, String streamType,
            UUID streamId, long streamVersion) {
        List<QuarantineState> rows = jdbcTemplate.query("""
                SELECT quarantine.id, quarantine.merchant_id,
                       quarantine.domain_event_id, quarantine.lifecycle_status,
                       inbox.topic_name, inbox.broker_partition, inbox.broker_offset,
                       event.stream_type, event.stream_id, event.stream_version,
                       event.recorded_at
                FROM consumer_quarantine quarantine
                JOIN consumer_inbox inbox
                  ON inbox.merchant_id = quarantine.merchant_id
                 AND inbox.consumer_name = quarantine.consumer_name
                 AND inbox.domain_event_id = quarantine.domain_event_id
                JOIN domain_events event
                  ON event.merchant_id = quarantine.merchant_id
                 AND event.id = quarantine.domain_event_id
                WHERE quarantine.merchant_id = ?
                  AND quarantine.consumer_name = ?
                  AND quarantine.lifecycle_status = 'OPEN'
                  AND inbox.lifecycle_status = 'HELD'
                  AND event.stream_type = ? AND event.stream_id = ?
                  AND event.stream_version = ?
                """, (resultSet, rowNumber) -> new QuarantineState(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getString(4),
                        resultSet.getString(5), resultSet.getInt(6), resultSet.getLong(7),
                        resultSet.getString(8), resultSet.getObject(9, UUID.class),
                        resultSet.getLong(10), JdbcTime.instant(resultSet, 11)),
                merchantId, ProjectionNames.PAYMENT_RISK_CONSUMER, streamType,
                streamId, streamVersion);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean sameQuarantineIdentity(QuarantineState expected,
            QuarantineState actual) {
        return actual != null
                && expected.quarantineId().equals(actual.quarantineId())
                && expected.merchantId().equals(actual.merchantId())
                && expected.domainEventId().equals(actual.domainEventId())
                && expected.topicName().equals(actual.topicName())
                && expected.brokerPartition() == actual.brokerPartition()
                && expected.brokerOffset() == actual.brokerOffset()
                && expected.streamType().equals(actual.streamType())
                && expected.streamId().equals(actual.streamId())
                && expected.streamVersion() == actual.streamVersion();
    }

    private void advanceStreamPosition(CanonicalDomainEventRecord event, UUID generationId,
            Instant processedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE projection_stream_positions
                SET last_stream_version = ?, last_domain_event_id = ?,
                    last_event_record_sha256 = ?, last_event_occurred_at = ?,
                    last_event_recorded_at = ?, last_processed_at = ?
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND stream_type = ? AND stream_id = ?
                """, event.streamVersion(), event.eventId(), event.eventRecordSha256(),
                timestamp(event.occurredAt()), timestamp(event.recordedAt()),
                timestamp(processedAt), event.merchantId(),
                generationId, event.streamType().name(), event.streamId());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO projection_stream_positions (
                        id, merchant_id, projection_generation_id, stream_type, stream_id,
                        last_stream_version, last_domain_event_id,
                        last_event_record_sha256, last_event_occurred_at,
                        last_event_recorded_at, last_processed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), event.merchantId(), generationId,
                    event.streamType().name(), event.streamId(), event.streamVersion(),
                    event.eventId(), event.eventRecordSha256(), timestamp(event.occurredAt()),
                    timestamp(event.recordedAt()), timestamp(processedAt));
        }
    }

    private void advanceCheckpoint(CanonicalDomainEventRecord event,
            TransportedDomainEvent transported, UUID generationId, Instant processedAt) {
        List<CheckpointState> rows = jdbcTemplate.query("""
                SELECT id, last_broker_offset
                FROM projection_checkpoints
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND consumer_name = ? AND topic_name = ? AND broker_partition = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new CheckpointState(
                        resultSet.getObject(1, UUID.class), resultSet.getLong(2)),
                event.merchantId(), generationId, ProjectionNames.PAYMENT_RISK_CONSUMER,
                transported.topic(), transported.partition());
        if (rows.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO projection_checkpoints (
                        id, merchant_id, projection_generation_id, consumer_name,
                        topic_name, broker_partition, last_broker_offset,
                        last_domain_event_id, last_event_occurred_at,
                        last_event_recorded_at, last_processed_at, lifecycle_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CURRENT')
                    """, UUID.randomUUID(), event.merchantId(), generationId,
                    ProjectionNames.PAYMENT_RISK_CONSUMER, transported.topic(),
                    transported.partition(), transported.offset(), event.eventId(),
                    timestamp(event.occurredAt()), timestamp(event.recordedAt()),
                    timestamp(processedAt));
            return;
        }
        CheckpointState checkpoint = rows.getFirst();
        if (transported.offset() <= checkpoint.offset()) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE projection_checkpoints
                SET last_broker_offset = ?, last_domain_event_id = ?,
                    last_event_occurred_at = ?, last_event_recorded_at = ?,
                    last_processed_at = ?, lifecycle_status = 'CURRENT'
                WHERE id = ?
                """, transported.offset(), event.eventId(), timestamp(event.occurredAt()),
                timestamp(event.recordedAt()), timestamp(processedAt), checkpoint.id());
    }

    private void insertInbox(UUID merchantId, UUID eventId, String recordSha256,
            UUID generationId, String lifecycleStatus, TransportedDomainEvent transported,
            Instant processedAt) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO consumer_inbox (
                        id, merchant_id, consumer_name, domain_event_id,
                        event_record_sha256, handler_version, projection_name,
                        projection_generation_id, lifecycle_status, topic_name,
                        broker_partition, broker_offset, created_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), merchantId,
                    ProjectionNames.PAYMENT_RISK_CONSUMER, eventId, recordSha256,
                    ProjectionNames.HANDLER_VERSION, ProjectionNames.PAYMENT_RISK,
                    generationId, lifecycleStatus, transported.topic(),
                    transported.partition(), transported.offset(), timestamp(processedAt),
                    timestamp(processedAt));
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Concurrent consumer inbox reservation escaped locking",
                    exception);
        }
    }

    private Instant safeProcessedAt(Instant recordedAt) {
        Instant now = clock.instant();
        return now.isBefore(recordedAt) ? recordedAt : now;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private record InboxState(String recordSha256, String lifecycleStatus) {
    }

    private record CheckpointState(UUID id, long offset) {
    }

    private record QuarantineState(UUID quarantineId, UUID merchantId,
            UUID domainEventId, String lifecycleStatus, String topicName,
            int brokerPartition, long brokerOffset, String streamType,
            UUID streamId, long streamVersion, Instant recordedAt) {
    }

    private record TransportQuarantineState(String recordSha256, long recordSizeBytes,
            ConsumerQuarantineReason reason) {

        private boolean matches(String candidateDigest, long candidateSize,
                ConsumerQuarantineReason candidateReason) {
            return constantTimeDigest(recordSha256, candidateDigest)
                    && recordSizeBytes == candidateSize && reason == candidateReason;
        }

        private static boolean constantTimeDigest(String first, String second) {
            return first != null && second != null && MessageDigest.isEqual(
                    first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    private static final class QuarantinableProjectionException extends RuntimeException {
        private final ConsumerQuarantineReason reason;

        private QuarantinableProjectionException(ConsumerQuarantineReason reason) {
            super("Projection input requires quarantine: " + reason.name());
            this.reason = reason;
        }

        private ConsumerQuarantineReason reason() {
            return reason;
        }
    }
}
