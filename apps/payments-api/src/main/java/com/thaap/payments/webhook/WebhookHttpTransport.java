package com.thaap.payments.webhook;

public interface WebhookHttpTransport {

    WebhookHttpResponse send(WebhookHttpRequest request);
}
