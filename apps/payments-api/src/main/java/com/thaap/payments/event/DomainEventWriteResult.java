package com.thaap.payments.event;

import java.time.Instant;
import java.util.UUID;

public record DomainEventWriteResult(UUID eventId, UUID publicationId, UUID merchantId,
        DomainStreamType streamType, UUID streamId, long streamVersion,
        DomainEventType eventType, int eventVersion, Instant occurredAt, Instant recordedAt,
        String payloadSha256, String eventRecordSha256) {
}
