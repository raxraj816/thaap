package com.thaap.payments.projection;

import com.thaap.payments.event.DomainEventProtocolViolation;

public enum ConsumerQuarantineReason {
    UNSUPPORTED_EVENT_TYPE,
    UNSUPPORTED_EVENT_VERSION,
    INVALID_ENVELOPE,
    INVALID_SCHEMA,
    RECORD_KEY_MISMATCH,
    PAYLOAD_DIGEST_MISMATCH,
    RECORD_DIGEST_MISMATCH,
    TENANT_STREAM_DISAGREEMENT,
    PAYLOAD_LIMIT_EXCEEDED,
    SOURCE_IDENTITY_UNAVAILABLE,
    SOURCE_IDENTITY_CONFLICT,
    IMPOSSIBLE_STREAM_VERSION,
    MISSING_PREDECESSOR,
    PREDECESSOR_UNAVAILABLE,
    PROJECTION_INVARIANT_FAILURE;

    public static ConsumerQuarantineReason fromProtocol(DomainEventProtocolViolation violation) {
        return valueOf(violation.name());
    }
}
