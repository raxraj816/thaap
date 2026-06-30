package com.thaap.payments.webhook;

public class WebhookTransportException extends RuntimeException {

    private final WebhookDeliveryErrorCode errorCode;
    private final Boolean requestSent;

    public WebhookTransportException(String message, WebhookDeliveryErrorCode errorCode,
            Boolean requestSent, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.requestSent = requestSent;
    }

    public WebhookDeliveryErrorCode getErrorCode() {
        return errorCode;
    }

    public Boolean getRequestSent() {
        return requestSent;
    }
}
