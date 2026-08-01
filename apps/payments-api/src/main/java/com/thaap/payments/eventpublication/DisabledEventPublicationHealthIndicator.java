package com.thaap.payments.eventpublication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("eventPublication")
@ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "false")
public class DisabledEventPublicationHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        return Health.down()
                .withDetail("cause", "EVENT_PUBLICATION_DISABLED")
                .withDetail("topicClass", "DOMAIN_EVENTS_V1")
                .build();
    }
}
