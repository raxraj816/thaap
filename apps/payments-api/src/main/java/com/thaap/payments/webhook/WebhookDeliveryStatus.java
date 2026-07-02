package com.thaap.payments.webhook;

public enum WebhookDeliveryStatus {
    PENDING,
    DELIVERING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    EXHAUSTED
}
