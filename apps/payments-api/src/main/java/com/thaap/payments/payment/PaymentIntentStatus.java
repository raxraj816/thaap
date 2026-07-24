package com.thaap.payments.payment;

public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    // Reserved for durable fraud lineage and feature snapshots.
    REQUIRES_FRAUD_CHECK,
    REQUIRES_ACTION,
    PROCESSING,
    AUTHORIZED,
    REQUIRES_CAPTURE,
    CAPTURED,
    BLOCKED,
    REQUIRES_REVIEW,
    CANCELED,
    REFUNDED,
    DISPUTED,
    FAILED
}
