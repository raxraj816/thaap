package com.thaap.payments.webhook;

public class WebhookSecretException extends RuntimeException {

    private final WebhookDeliveryErrorCode errorCode;

    public WebhookSecretException(String message, WebhookDeliveryErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public WebhookSecretException(String message, WebhookDeliveryErrorCode errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public WebhookDeliveryErrorCode getErrorCode() {
        return errorCode;
    }
}
