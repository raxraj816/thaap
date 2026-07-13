package com.thaap.payments.webhook;

import java.util.Arrays;

public enum WebhookEventType {
    PAYMENT_INTENT_CREATED("payment_intent.created", WebhookResourceType.PAYMENT_INTENT),
    PAYMENT_ATTEMPT_CREATED("payment_attempt.created", WebhookResourceType.PAYMENT_ATTEMPT),
    PAYMENT_INTENT_CAPTURED("payment_intent.captured", WebhookResourceType.PAYMENT_INTENT),
    PAYMENT_INTENT_CANCELED("payment_intent.canceled", WebhookResourceType.PAYMENT_INTENT),
    PAYMENT_INTENT_BLOCKED("payment_intent.blocked", WebhookResourceType.PAYMENT_INTENT),
    PAYMENT_INTENT_REQUIRES_ACTION("payment_intent.requires_action", WebhookResourceType.PAYMENT_INTENT),
    FRAUD_REVIEW_OPENED("fraud_review.opened", WebhookResourceType.MANUAL_REVIEW_CASE),
    FRAUD_REVIEW_CANCELED("fraud_review.canceled", WebhookResourceType.MANUAL_REVIEW_CASE),
    REFUND_CREATED("refund.created", WebhookResourceType.REFUND);

    private final String wireValue;
    private final WebhookResourceType resourceType;

    WebhookEventType(String wireValue, WebhookResourceType resourceType) {
        this.wireValue = wireValue;
        this.resourceType = resourceType;
    }

    public String wireValue() {
        return wireValue;
    }

    public WebhookResourceType resourceType() {
        return resourceType;
    }

    public static WebhookEventType fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported webhook event type: " + value));
    }
}
