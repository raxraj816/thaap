package com.thaap.payments.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.UUID;

public record CanonicalDomainEventRecord(
        UUID eventId,
        UUID merchantId,
        DomainStreamType streamType,
        UUID streamId,
        long streamVersion,
        DomainEventType eventType,
        int eventVersion,
        Instant occurredAt,
        Instant recordedAt,
        String correlationId,
        UUID causationEventId,
        String requestId,
        String traceId,
        DomainEventActor actor,
        TrafficOrigin trafficOrigin,
        ObjectNode payload,
        String payloadSha256,
        byte[] eventRecordBytes,
        String eventRecordSha256) {

    public CanonicalDomainEventRecord {
        payload = payload.deepCopy();
        eventRecordBytes = eventRecordBytes.clone();
    }

    @Override
    public ObjectNode payload() {
        return payload.deepCopy();
    }

    @Override
    public byte[] eventRecordBytes() {
        return eventRecordBytes.clone();
    }
}
