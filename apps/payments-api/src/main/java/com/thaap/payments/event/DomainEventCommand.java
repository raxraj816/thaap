package com.thaap.payments.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public, typed boundary used by future payment and risk producer integrations. */
public record DomainEventCommand(UUID merchantId, DomainStreamType streamType, UUID streamId,
        DomainEventType eventType, int eventVersion, Instant occurredAt, String correlationId,
        UUID causationEventId, String requestId, String traceId, DomainEventActor actor,
        TrafficOrigin trafficOrigin, ObjectNode payload) {

    public DomainEventCommand {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(streamType, "streamType");
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("Domain event version must be positive");
        }
        if (streamType != eventType.streamType()) {
            throw new IllegalArgumentException("Domain event type does not belong to the supplied stream type");
        }
        correlationId = boundedRequired(correlationId, "correlationId");
        requestId = boundedOptional(requestId, "requestId");
        traceId = boundedOptional(traceId, "traceId");
        payload = payload.deepCopy();
    }

    @Override
    public ObjectNode payload() {
        return payload.deepCopy();
    }

    private static String boundedRequired(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
        }
        return value;
    }

    private static String boundedOptional(String value, String field) {
        if (value != null && (value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException(field + " must be absent or contain 1 to 128 characters");
        }
        return value;
    }
}
