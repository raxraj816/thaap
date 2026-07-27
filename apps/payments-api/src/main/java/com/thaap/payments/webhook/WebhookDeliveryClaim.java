package com.thaap.payments.webhook;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryClaim(
        UUID deliveryId,
        UUID merchantId,
        UUID endpointId,
        UUID eventId,
        String eventType,
        String endpointUrl,
        String payload,
        String payloadSha256,
        UUID claimToken,
        int attemptNumber,
        UUID signingSecretGeneration,
        Instant claimedAt,
        Instant deliveryCreatedAt,
        int deliveryGeneration,
        int generationAttemptNumber,
        UUID redeliveryRequestId) {

    public WebhookDeliveryClaim(
            UUID deliveryId,
            UUID merchantId,
            UUID endpointId,
            UUID eventId,
            String eventType,
            String endpointUrl,
            String payload,
            String payloadSha256,
            UUID claimToken,
            int attemptNumber,
            UUID signingSecretGeneration,
            Instant claimedAt,
            Instant deliveryCreatedAt) {
        this(deliveryId, merchantId, endpointId, eventId, eventType, endpointUrl, payload,
                payloadSha256, claimToken, attemptNumber, signingSecretGeneration,
                claimedAt, deliveryCreatedAt, 1, attemptNumber, null);
    }

    public WebhookDeliveryClaim(
            UUID deliveryId,
            UUID merchantId,
            UUID endpointId,
            UUID eventId,
            String eventType,
            String endpointUrl,
            String payload,
            String payloadSha256,
            UUID claimToken,
            int attemptNumber,
            Instant claimedAt,
            Instant deliveryCreatedAt) {
        this(deliveryId, merchantId, endpointId, eventId, eventType, endpointUrl, payload,
                payloadSha256, claimToken, attemptNumber, null, claimedAt, deliveryCreatedAt,
                1, attemptNumber, null);
    }
}
