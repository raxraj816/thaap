package com.thaap.payments.webhook;

public class WebhookTargetPolicyException extends RuntimeException {

    public WebhookTargetPolicyException(String message) {
        super(message);
    }

    public WebhookTargetPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
