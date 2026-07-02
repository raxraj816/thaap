package com.thaap.payments.webhook;

public record WebhookHttpResponse(int statusCode) {

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode <= 299;
    }
}
