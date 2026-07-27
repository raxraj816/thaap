package com.thaap.payments.webhook;

public enum WebhookRedeliveryReason {
    MANUAL_RETRY,
    ENDPOINT_RECOVERED,
    RECEIVER_REPAIRED
}
