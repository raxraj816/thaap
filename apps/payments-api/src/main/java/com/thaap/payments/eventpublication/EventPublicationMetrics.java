package com.thaap.payments.eventpublication;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class EventPublicationMetrics {

    private static final String COMPONENT = "event_publisher";
    private static final String TOPIC_CLASS = "DOMAIN_EVENTS_V1";

    private final Counter claimed;
    private final Counter staleFinalizers;
    private final Counter expiredFinalizers;
    private final Map<PublicationOutcome, Counter> outcomes;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final Clock clock;

    public EventPublicationMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        this.claimed = counter(registry, "thaap.event.publication.claimed", null);
        this.staleFinalizers = counter(registry,
                "thaap.event.publication.finalizer.rejected", "STALE_TOKEN");
        this.expiredFinalizers = counter(registry,
                "thaap.event.publication.finalizer.rejected", "EXPIRED_LEASE");
        this.outcomes = new EnumMap<>(PublicationOutcome.class);
        for (PublicationOutcome outcome : PublicationOutcome.values()) {
            outcomes.put(outcome, counter(registry,
                    "thaap.event.publication.outcome", outcome.name()));
        }
        Gauge.builder("thaap.event.publication.pending", pending, AtomicLong::get)
                .tag("component", COMPONENT).tag("topicClass", TOPIC_CLASS)
                .register(registry);
        Gauge.builder("thaap.event.publication.blocked", blocked, AtomicLong::get)
                .tag("component", COMPONENT).tag("topicClass", TOPIC_CLASS)
                .register(registry);
        Gauge.builder("thaap.event.publication.oldest.age.seconds", oldestAgeSeconds,
                        AtomicLong::get)
                .tag("component", COMPONENT).tag("topicClass", TOPIC_CLASS)
                .register(registry);
    }

    public void claimed(int count) {
        claimed.increment(count);
    }

    public void outcome(PublicationOutcome outcome) {
        outcomes.get(outcome).increment();
    }

    public void finalizationRejected(PublicationFinalization finalization) {
        if (finalization == PublicationFinalization.STALE_FENCING_TOKEN) {
            staleFinalizers.increment();
        } else if (finalization == PublicationFinalization.EXPIRED_LEASE) {
            expiredFinalizers.increment();
        }
    }

    public void observeBacklog(PublicationHealthSnapshot snapshot) {
        pending.set(snapshot.pendingCount() + snapshot.claimedCount()
                + snapshot.retryingCount());
        blocked.set(snapshot.blockedCount());
        Instant oldest = snapshot.oldestUnpublishedAt();
        oldestAgeSeconds.set(oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, clock.instant()).toSeconds()));
    }

    private Counter counter(MeterRegistry registry, String name, String outcome) {
        Counter.Builder builder = Counter.builder(name)
                .tag("component", COMPONENT).tag("topicClass", TOPIC_CLASS);
        if (outcome != null) {
            builder.tag("outcome", outcome);
        }
        return builder.register(registry);
    }
}
