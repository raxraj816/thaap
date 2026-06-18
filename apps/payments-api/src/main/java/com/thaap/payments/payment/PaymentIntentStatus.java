package com.thaap.payments.payment;

public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    // Reserved for Chapter 12 durable fraud lineage and feature snapshots.
    REQUIRES_FRAUD_CHECK,
    REQUIRES_ACTION,
    PROCESSING,
    AUTHORIZED,
    REQUIRES_CAPTURE,
    CAPTURED,
    CANCELED,
    REFUNDED,
    DISPUTED,
    FAILED
}
