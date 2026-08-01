package com.thaap.payments.projection.rebuild;

import com.thaap.payments.event.CanonicalDomainEventRecord;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.projection.ActiveProjectionGeneration;
import com.thaap.payments.projection.PaymentRiskProjectionHandler;
import com.thaap.payments.projection.ProjectionNames;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProjectionRebuildApplier {

    private final CanonicalEventCodec codec;
    private final PaymentRiskProjectionHandler handler;
    private final ProjectionBootstrapFactApplier bootstrapFactApplier;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ProjectionRebuildApplier(CanonicalEventCodec codec,
            PaymentRiskProjectionHandler handler,
            ProjectionBootstrapFactApplier bootstrapFactApplier,
            JdbcTemplate jdbcTemplate, Clock clock) {
        this.codec = codec;
        this.handler = handler;
        this.bootstrapFactApplier = bootstrapFactApplier;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void apply(ProjectionRebuildInput input, ProjectionRebuildLease lease) {
        if (input.kind() == ProjectionRebuildInput.Kind.BOOTSTRAP_FACT) {
            ProjectionBootstrapPaymentFact fact = bootstrapFactApplier.apply(input, lease);
            advanceBootstrapStreamPosition(fact, lease.projectionGenerationId());
            return;
        }
        CanonicalDomainEventRecord event = codec.decodeAndValidate(
                input.canonicalRecordBytes(), input.canonicalRecordSha256());
        if (!event.eventId().equals(input.inputId())
                || !event.merchantId().equals(input.merchantId())
                || !event.streamType().name().equals(input.streamType())
                || !event.streamId().equals(input.streamId())
                || event.streamVersion() != input.streamVersion()) {
            throw new IllegalStateException("Rebuild source event identity is inconsistent");
        }
        ActiveProjectionGeneration generation = new ActiveProjectionGeneration(
                lease.projectionDefinitionId(), lease.projectionGenerationId(), 0,
                lease.handlerVersion());
        Instant processedAt = processedAt(event.recordedAt());
        if (ProjectionNames.PAYMENT_RISK.equals(lease.projectionName())) {
            handler.applyPaymentOnly(event, generation, processedAt);
        } else if (ProjectionNames.MERCHANT_RISK.equals(lease.projectionName())) {
            handler.applyMerchantOnly(event, generation, processedAt);
        } else {
            throw new IllegalStateException("Projection rebuild target is unsupported");
        }
        advanceStreamPosition(event, generation.generationId(), processedAt);
    }

    private void advanceBootstrapStreamPosition(ProjectionBootstrapPaymentFact fact,
            UUID generationId) {
        if (fact.baseStreamVersion() == 0) {
            return;
        }
        List<EventTime> eventTimes = jdbcTemplate.query("""
                SELECT occurred_at, recorded_at FROM domain_events
                WHERE merchant_id = ? AND id = ? AND stream_type = 'PAYMENT'
                  AND stream_id = ? AND stream_version = ?
                  AND event_record_sha256 = ?
                """, (resultSet, rowNumber) -> new EventTime(
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 1),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 2)),
                fact.merchantId(),
                fact.baseTerminalDomainEventId(), fact.paymentIntentId(),
                fact.baseStreamVersion(), fact.baseTerminalEventRecordSha256());
        if (eventTimes.size() != 1) {
            throw new IllegalStateException("Projection bootstrap event base is unavailable");
        }
        EventTime eventTime = eventTimes.getFirst();
        Instant processedAt = processedAt(eventTime.recordedAt());
        int inserted = jdbcTemplate.update("""
                INSERT INTO projection_stream_positions (
                    id, merchant_id, projection_generation_id, stream_type, stream_id,
                    last_stream_version, last_domain_event_id,
                    last_event_record_sha256, last_event_occurred_at,
                    last_event_recorded_at, last_processed_at)
                VALUES (?, ?, ?, 'PAYMENT', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), fact.merchantId(), generationId,
                fact.paymentIntentId(), fact.baseStreamVersion(),
                fact.baseTerminalDomainEventId(), fact.baseTerminalEventRecordSha256(),
                Timestamp.from(eventTime.occurredAt()),
                Timestamp.from(eventTime.recordedAt()), Timestamp.from(processedAt));
        if (inserted != 1) {
            throw new IllegalStateException("Projection bootstrap stream base was not singular");
        }
    }

    private void advanceStreamPosition(CanonicalDomainEventRecord event, UUID generationId,
            Instant processedAt) {
        List<Long> current = jdbcTemplate.query("""
                SELECT last_stream_version FROM projection_stream_positions
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND stream_type = ? AND stream_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getLong(1), event.merchantId(),
                generationId, event.streamType().name(), event.streamId());
        if (current.isEmpty()) {
            if (event.streamVersion() != 1) {
                throw new IllegalStateException("Rebuild source stream has a missing predecessor");
            }
            jdbcTemplate.update("""
                    INSERT INTO projection_stream_positions (
                        id, merchant_id, projection_generation_id, stream_type, stream_id,
                        last_stream_version, last_domain_event_id,
                        last_event_record_sha256, last_event_occurred_at,
                        last_event_recorded_at, last_processed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), event.merchantId(), generationId,
                    event.streamType().name(), event.streamId(), event.streamVersion(),
                    event.eventId(), event.eventRecordSha256(),
                    Timestamp.from(event.occurredAt()), Timestamp.from(event.recordedAt()),
                    Timestamp.from(processedAt));
            return;
        }
        if (current.size() != 1 || current.getFirst() + 1 != event.streamVersion()) {
            throw new IllegalStateException("Rebuild source stream order is invalid");
        }
        int changed = jdbcTemplate.update("""
                UPDATE projection_stream_positions
                SET last_stream_version = ?, last_domain_event_id = ?,
                    last_event_record_sha256 = ?, last_event_occurred_at = ?,
                    last_event_recorded_at = ?, last_processed_at = ?
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND stream_type = ? AND stream_id = ?
                  AND last_stream_version = ?
                """, event.streamVersion(), event.eventId(), event.eventRecordSha256(),
                Timestamp.from(event.occurredAt()), Timestamp.from(event.recordedAt()),
                Timestamp.from(processedAt), event.merchantId(), generationId,
                event.streamType().name(), event.streamId(), current.getFirst());
        if (changed != 1) {
            throw new ProjectionRebuildFenceException();
        }
    }

    private Instant processedAt(Instant recordedAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(recordedAt) ? recordedAt : now;
    }

    private record EventTime(Instant occurredAt, Instant recordedAt) {
    }
}
