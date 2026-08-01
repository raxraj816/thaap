package com.thaap.payments.projection;

import java.util.UUID;

public record TransportedDomainEvent(
        String topic,
        int partition,
        long offset,
        byte[] recordKey,
        byte[] eventRecordBytes,
        UUID sourceEventId,
        UUID sourceMerchantId,
        String eventRecordSha256) {

    public TransportedDomainEvent {
        recordKey = recordKey == null ? null : recordKey.clone();
        eventRecordBytes = eventRecordBytes == null ? null : eventRecordBytes.clone();
    }

    @Override
    public byte[] recordKey() {
        return recordKey == null ? null : recordKey.clone();
    }

    @Override
    public byte[] eventRecordBytes() {
        return eventRecordBytes == null ? null : eventRecordBytes.clone();
    }
}
