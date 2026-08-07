package com.thaap.payments.event;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the emitted catalogue version and whether this instance is producing at all.
 *
 * <p>During a rolling deployment an operator needs to tell "this instance is deliberately not
 * producing" from "this instance is stuck", which is the same distinction the worker health
 * indicators draw. An unsupported configured version cannot reach here, because
 * {@link DomainEventProducerGate} refuses to let the instance start.
 */
@Component("domainEventProducer")
public class DomainEventProducerHealthIndicator implements HealthIndicator {

    private final DomainEventProducerGate gate;

    public DomainEventProducerHealthIndicator(DomainEventProducerGate gate) {
        this.gate = gate;
    }

    @Override
    public Health health() {
        if (!gate.producerEnabled()) {
            return Health.outOfService()
                    .withDetail("state", "PRODUCER_DISABLED")
                    .withDetail("producerVersion", gate.producerVersion())
                    .build();
        }
        return Health.up()
                .withDetail("state", "PRODUCING")
                .withDetail("producerVersion", gate.producerVersion())
                .build();
    }
}
