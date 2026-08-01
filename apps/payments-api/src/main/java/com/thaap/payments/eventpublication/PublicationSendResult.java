package com.thaap.payments.eventpublication;

public record PublicationSendResult(PublicationOutcome outcome,
        PublicationSafeErrorCode safeErrorCode, Integer brokerPartition,
        Long brokerOffset) {

    public PublicationSendResult {
        if (outcome == null) {
            throw new IllegalArgumentException("Publication outcome is required");
        }
        if (outcome == PublicationOutcome.ACKNOWLEDGED) {
            if (safeErrorCode != null || brokerPartition == null
                    || brokerPartition < 0 || brokerPartition >= 6
                    || brokerOffset == null || brokerOffset < 0) {
                throw new IllegalArgumentException(
                        "Acknowledgement requires valid broker evidence and no error");
            }
        } else if (safeErrorCode == null || brokerPartition != null
                || brokerOffset != null) {
            throw new IllegalArgumentException(
                    "Non-acknowledgement requires one safe error and no broker position");
        }
    }

    public static PublicationSendResult acknowledged(int partition, long offset) {
        return new PublicationSendResult(PublicationOutcome.ACKNOWLEDGED,
                null, partition, offset);
    }

    public static PublicationSendResult failed(PublicationOutcome outcome,
            PublicationSafeErrorCode safeErrorCode) {
        return new PublicationSendResult(outcome, safeErrorCode, null, null);
    }
}
