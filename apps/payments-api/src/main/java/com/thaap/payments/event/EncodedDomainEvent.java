package com.thaap.payments.event;

import java.time.Instant;

public record EncodedDomainEvent(Instant occurredAt, Instant recordedAt, byte[] payloadBytes,
        String payloadSha256, byte[] eventRecordBytes, String eventRecordSha256) {

    public EncodedDomainEvent {
        payloadBytes = payloadBytes.clone();
        eventRecordBytes = eventRecordBytes.clone();
    }

    @Override
    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }

    @Override
    public byte[] eventRecordBytes() {
        return eventRecordBytes.clone();
    }
}
