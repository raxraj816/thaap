package com.thaap.payments.webhook;

import java.util.UUID;

public record WebhookHttpRequest(
        ValidatedWebhookTarget target,
        UUID eventId,
        UUID deliveryId,
        String signatureHeader,
        String payload) {
}
