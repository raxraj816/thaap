package com.thaap.payments.webhook;

public record WebhookSignature(long timestampSeconds, String value, String headerValue) {
}
