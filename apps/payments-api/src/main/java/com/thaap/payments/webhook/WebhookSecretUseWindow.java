package com.thaap.payments.webhook;

import java.time.Instant;

public record WebhookSecretUseWindow(Instant expiresAt) {

    public static WebhookSecretUseWindow unbounded() {
        return new WebhookSecretUseWindow(null);
    }

    public static WebhookSecretUseWindow bounded(Instant expiresAt) {
        if (expiresAt == null) {
            throw new IllegalArgumentException("Bounded webhook secret use requires an expiry");
        }
        return new WebhookSecretUseWindow(expiresAt);
    }

    public void requireUsableAt(Instant signatureTime) {
        if (expiresAt != null && !signatureTime.isBefore(expiresAt)) {
            throw new WebhookSecretException(
                    "Webhook signing secret generation is expired at signature time",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
    }
}
