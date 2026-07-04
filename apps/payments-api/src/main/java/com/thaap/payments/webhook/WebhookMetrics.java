package com.thaap.payments.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class WebhookMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong dueDeliveries;

    public WebhookMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.dueDeliveries = new AtomicLong();
        Gauge.builder("thaap.webhook.due", dueDeliveries, AtomicLong::get)
                .description("Webhook deliveries currently eligible for claim")
                .register(meterRegistry);
    }

    public void recordAttempt(String eventType, String outcome, Duration duration) {
        Counter.builder("thaap.webhook.attempts")
                .tag("event.type", eventType)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
        Timer.builder("thaap.webhook.attempt.duration")
                .tag("event.type", eventType)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(nonNegative(duration));
    }

    public void recordTerminal(String eventType, WebhookDeliveryStatus status, Duration deliveryLatency) {
        recordTerminalCount(eventType, status, 1);
        Timer.builder("thaap.webhook.delivery.latency")
                .tag("event.type", eventType)
                .tag("status", status.name())
                .register(meterRegistry)
                .record(nonNegative(deliveryLatency));
    }

    public void recordTerminalCount(String eventType, WebhookDeliveryStatus status, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("thaap.webhook.delivery.terminal")
                .tag("event.type", eventType)
                .tag("status", status.name())
                .register(meterRegistry)
                .increment(count);
    }

    public void recordFailure(String eventType, WebhookDeliveryErrorCode errorCode) {
        recordFailureCount(eventType, errorCode, 1);
    }

    public void recordFailureCount(String eventType, WebhookDeliveryErrorCode errorCode, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("thaap.webhook.failures")
                .tag("event.type", eventType)
                .tag("error.code", errorCode.name())
                .register(meterRegistry)
                .increment(count);
    }

    public void recordQueueAge(String eventType, Duration queueAge) {
        Timer.builder("thaap.webhook.queue.age")
                .tag("event.type", eventType)
                .register(meterRegistry)
                .record(nonNegative(queueAge));
    }

    public void recordClaimThrottled(String scope) {
        Counter.builder("thaap.webhook.claim.throttled")
                .tag("scope", scope)
                .description("Webhook claim budgets reached during a scheduler poll")
                .register(meterRegistry)
                .increment();
    }

    public void setDueDeliveries(long count) {
        dueDeliveries.set(Math.max(0, count));
    }

    private Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
