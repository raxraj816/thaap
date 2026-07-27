package com.thaap.payments.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookVerificationExpiryScheduler {

    private final WebhookEndpointVerificationService service;
    private final boolean enabled;
    private final int batchSize;

    public WebhookVerificationExpiryScheduler(WebhookEndpointVerificationService service,
            @Value("${thaap.webhook.verification-expiry-enabled:true}") boolean enabled,
            @Value("${thaap.webhook.verification-expiry-batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "Webhook verification expiry batch size must be between 1 and 100");
        }
        this.service = service;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${thaap.webhook.verification-expiry-poll-delay:30s}")
    public void expireDue() {
        if (enabled) {
            service.expireDue(batchSize);
        }
    }
}
