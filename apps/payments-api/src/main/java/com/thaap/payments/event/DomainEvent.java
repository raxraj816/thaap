package com.thaap.payments.event;

import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "domain_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_domain_events_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_domain_events_stream_version",
                columnNames = {"merchant_id", "stream_type", "stream_id", "stream_version"})
})
public class DomainEvent {

    @Id
    private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Enumerated(EnumType.STRING) @Column(name = "stream_type", nullable = false, length = 32)
    private DomainStreamType streamType;
    @Column(name = "stream_id", nullable = false) private UUID streamId;
    @Column(name = "stream_version", nullable = false) private long streamVersion;
    @Convert(converter = DomainEventTypeConverter.class)
    @Column(name = "event_type", nullable = false, length = 80)
    private DomainEventType eventType;
    @Column(name = "event_version", nullable = false) private int eventVersion;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;
    @Column(name = "correlation_id", nullable = false, length = 128) private String correlationId;
    @Column(name = "causation_event_id") private UUID causationEventId;
    @Column(name = "request_id", length = 128) private String requestId;
    @Column(name = "trace_id", length = 128) private String traceId;
    @Enumerated(EnumType.STRING) @Column(name = "actor_type", length = 32)
    private DomainEventActorType actorType;
    @Column(name = "actor_id", length = 128) private String actorId;
    @Enumerated(EnumType.STRING) @Column(name = "traffic_origin", length = 32)
    private TrafficOrigin trafficOrigin;
    @Column(name = "payload_bytes", nullable = false, columnDefinition = "BYTEA") private byte[] payloadBytes;
    @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;
    @Column(name = "event_record_bytes", nullable = false, columnDefinition = "BYTEA")
    private byte[] eventRecordBytes;
    @Column(name = "event_record_sha256", nullable = false, length = 64) private String eventRecordSha256;

    protected DomainEvent() {
    }

    public DomainEvent(UUID id, DomainEventCommand command, long streamVersion,
            EncodedDomainEvent encoded) {
        this.id = id;
        this.merchantId = command.merchantId();
        this.streamType = command.streamType();
        this.streamId = command.streamId();
        this.streamVersion = streamVersion;
        this.eventType = command.eventType();
        this.eventVersion = command.eventVersion();
        this.occurredAt = encoded.occurredAt();
        this.recordedAt = encoded.recordedAt();
        this.correlationId = command.correlationId();
        this.causationEventId = command.causationEventId();
        this.requestId = command.requestId();
        this.traceId = command.traceId();
        this.actorType = command.actor() == null ? null : command.actor().type();
        this.actorId = command.actor() == null ? null : command.actor().id();
        this.trafficOrigin = command.trafficOrigin();
        this.payloadBytes = encoded.payloadBytes();
        this.payloadSha256 = encoded.payloadSha256();
        this.eventRecordBytes = encoded.eventRecordBytes();
        this.eventRecordSha256 = encoded.eventRecordSha256();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public DomainStreamType getStreamType() { return streamType; }
    public UUID getStreamId() { return streamId; }
    public long getStreamVersion() { return streamVersion; }
    public DomainEventType getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getCorrelationId() { return correlationId; }
    public UUID getCausationEventId() { return causationEventId; }
    public String getRequestId() { return requestId; }
    public String getTraceId() { return traceId; }
    public DomainEventActorType getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public TrafficOrigin getTrafficOrigin() { return trafficOrigin; }
    public byte[] getPayloadBytes() { return payloadBytes.clone(); }
    public String getPayloadSha256() { return payloadSha256; }
    public byte[] getEventRecordBytes() { return eventRecordBytes.clone(); }
    public String getEventRecordSha256() { return eventRecordSha256; }
}
