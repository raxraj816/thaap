package com.thaap.payments.eventpublication;

public enum PublicationOutcome {
    ACKNOWLEDGED,
    RETRYABLE_FAILURE,
    UNKNOWN,
    PERMANENT_FAILURE,
    LEASE_RECOVERED
}
