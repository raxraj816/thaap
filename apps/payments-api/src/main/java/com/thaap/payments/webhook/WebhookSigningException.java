package com.thaap.payments.webhook;

public class WebhookSigningException extends RuntimeException {

    public WebhookSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
