package com.thaap.payments.projection.rebuild;

import java.time.Instant;
import java.util.UUID;

public record ProjectionRebuildInput(
        Kind kind,
        UUID inputId,
        UUID merchantId,
        String streamType,
        UUID streamId,
        long streamVersion,
        byte[] canonicalRecordBytes,
        String canonicalRecordSha256,
        Instant recordedAt) {

    public enum Kind {
        BOOTSTRAP_FACT,
        DOMAIN_EVENT
    }

    public ProjectionRebuildInput {
        if (kind == null || inputId == null || merchantId == null
                || canonicalRecordBytes == null || canonicalRecordSha256 == null
                || recordedAt == null || streamVersion < 0) {
            throw new IllegalArgumentException("Projection rebuild input is invalid");
        }
        canonicalRecordBytes = canonicalRecordBytes.clone();
    }

    public static ProjectionRebuildInput bootstrap(UUID factId, UUID merchantId,
            UUID paymentIntentId, long baseStreamVersion, byte[] canonicalBytes,
            String canonicalSha256, Instant sourceCutoffAt) {
        return new ProjectionRebuildInput(Kind.BOOTSTRAP_FACT, factId, merchantId,
                "PAYMENT", paymentIntentId, baseStreamVersion, canonicalBytes,
                canonicalSha256, sourceCutoffAt);
    }

    public static ProjectionRebuildInput event(UUID eventId, UUID merchantId,
            String streamType, UUID streamId, long streamVersion,
            byte[] canonicalBytes, String canonicalSha256, Instant recordedAt) {
        return new ProjectionRebuildInput(Kind.DOMAIN_EVENT, eventId, merchantId,
                streamType, streamId, streamVersion, canonicalBytes,
                canonicalSha256, recordedAt);
    }

    @Override
    public byte[] canonicalRecordBytes() {
        return canonicalRecordBytes.clone();
    }

    public String checkpointKey() {
        if (kind == Kind.BOOTSTRAP_FACT) {
            return "B|" + merchantId + "|" + streamId;
        }
        return "E|" + merchantId + "|" + streamType + "|" + streamId
                + "|" + streamVersion;
    }
}
