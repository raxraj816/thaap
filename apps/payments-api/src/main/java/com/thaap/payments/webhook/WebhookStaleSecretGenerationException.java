package com.thaap.payments.webhook;

public class WebhookStaleSecretGenerationException extends WebhookSecretException {

    public WebhookStaleSecretGenerationException(
            String message, WebhookDeliveryErrorCode errorCode) {
        super(message, errorCode);
    }
}
