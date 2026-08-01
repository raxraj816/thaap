package com.thaap.payments.eventpublication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class EventPublicationHealthPolicyTests {

    @Test
    void disabledRequiredPublisherFailsReadinessWithABoundedCause() {
        Health health = new DisabledEventPublicationHealthIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnly(
                org.assertj.core.data.MapEntry.entry(
                        "cause", "EVENT_PUBLICATION_DISABLED"),
                org.assertj.core.data.MapEntry.entry(
                        "topicClass", "DOMAIN_EVENTS_V1"));
    }
}
