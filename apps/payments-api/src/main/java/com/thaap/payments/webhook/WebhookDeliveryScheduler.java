package com.thaap.payments.webhook;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);

    private final WebhookDeliveryExecutor executor;
    private final Clock clock;
    private final boolean executionEnabled;
    private final int batchSize;

    public WebhookDeliveryScheduler(WebhookDeliveryExecutor executor, Clock clock,
            @Value("${thaap.webhook.execution-enabled:false}") boolean executionEnabled,
            @Value("${thaap.webhook.batch-size:20}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Webhook batch size must be between 1 and 100");
        }
        this.executor = executor;
        this.clock = clock;
        this.executionEnabled = executionEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${thaap.webhook.poll-delay:1s}")
    public void poll() {
        try {
            Instant now = clock.instant();
            if (executionEnabled) {
                executor.processDueDeliveries(now, batchSize);
            } else {
                executor.refreshDueDeliveries(now);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Webhook delivery poll failed", exception);
        }
    }
}
