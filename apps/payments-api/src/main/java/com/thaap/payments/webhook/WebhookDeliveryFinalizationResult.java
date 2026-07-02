package com.thaap.payments.webhook;

public record WebhookDeliveryFinalizationResult(
        boolean finalized,
        WebhookDeliveryStatus deliveryStatus,
        WebhookDeliveryErrorCode deliveryErrorCode,
        WebhookAttemptOutcome attemptOutcome,
        WebhookDeliveryErrorCode attemptErrorCode) {

    static WebhookDeliveryFinalizationResult stale() {
        return new WebhookDeliveryFinalizationResult(false, null, null, null, null);
    }

    static WebhookDeliveryFinalizationResult finalized(
            WebhookDelivery delivery, WebhookDeliveryAttempt attempt) {
        return new WebhookDeliveryFinalizationResult(
                true,
                delivery.getStatus(),
                delivery.getLastErrorCode(),
                attempt.getOutcome(),
                attempt.getErrorCode());
    }
}
