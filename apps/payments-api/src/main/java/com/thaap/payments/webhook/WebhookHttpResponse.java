package com.thaap.payments.webhook;

public record WebhookHttpResponse(int statusCode, String verificationToken) {

    public WebhookHttpResponse(int statusCode) {
        this(statusCode, null);
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode <= 299;
    }
}
