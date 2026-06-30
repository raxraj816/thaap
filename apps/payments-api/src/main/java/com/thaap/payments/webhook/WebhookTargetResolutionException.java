package com.thaap.payments.webhook;

public class WebhookTargetResolutionException extends RuntimeException {

    private final WebhookDeliveryErrorCode errorCode;

    public WebhookTargetResolutionException(String message,
            WebhookDeliveryErrorCode errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public WebhookDeliveryErrorCode getErrorCode() {
        return errorCode;
    }
}
