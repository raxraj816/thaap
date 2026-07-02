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
        Instant claimedAt,
        Instant deliveryCreatedAt) {
}
