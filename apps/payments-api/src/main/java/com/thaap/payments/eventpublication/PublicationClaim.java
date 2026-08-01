package com.thaap.payments.eventpublication;

import com.thaap.payments.event.DomainStreamType;
import java.time.Instant;
import java.util.UUID;

public record PublicationClaim(
        UUID publicationId,
        UUID merchantId,
        UUID domainEventId,
        DomainStreamType streamType,
        UUID streamId,
        long streamVersion,
        int eventVersion,
        byte[] eventRecordBytes,
        String eventRecordSha256,
        UUID fencingToken,
        String workerId,
        Instant claimedAt,
        Instant leaseExpiresAt,
        boolean leaseRecovered) {

    public PublicationClaim {
        eventRecordBytes = eventRecordBytes.clone();
    }

    @Override
    public byte[] eventRecordBytes() {
        return eventRecordBytes.clone();
    }

    public byte[] recordKey() {
        return TenantQualifiedStreamKey.encode(merchantId, streamType, streamId);
    }
}
