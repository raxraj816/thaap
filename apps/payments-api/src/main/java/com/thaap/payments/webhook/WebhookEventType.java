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
    REVIEW_ASSIGNED("review.assigned", WebhookResourceType.MANUAL_REVIEW_CASE),
    REVIEW_RELEASED("review.released", WebhookResourceType.MANUAL_REVIEW_CASE),
    REVIEW_RESOLVED("review.resolved", WebhookResourceType.MANUAL_REVIEW_CASE),
    FRAUD_LABEL_CREATED("fraud_label.created", WebhookResourceType.FRAUD_LABEL),
    AUTHENTICATION_CHALLENGE_CREATED("authentication_challenge.created",
            WebhookResourceType.AUTHENTICATION_CHALLENGE),
    AUTHENTICATION_CHALLENGE_COMPLETED("authentication_challenge.completed",
            WebhookResourceType.AUTHENTICATION_CHALLENGE),
    CHARGEBACK_CREATED("chargeback.created", WebhookResourceType.CHARGEBACK),
    DISPUTE_EVIDENCE_PACKET_CREATED("dispute_evidence_packet.created",
            WebhookResourceType.DISPUTE_EVIDENCE_PACKET),
    POST_AUTHORIZATION_SIGNAL_CREATED("post_authorization_signal.created",
            WebhookResourceType.POST_AUTHORIZATION_SIGNAL),
    CARD_TESTING_INCIDENT_OPENED("card_testing_incident.opened",
            WebhookResourceType.CARD_TESTING_INCIDENT),
    CARD_TESTING_INCIDENT_RESOLVED("card_testing_incident.resolved",
            WebhookResourceType.CARD_TESTING_INCIDENT),
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
