package com.thaap.payments.eventpublication;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "true")
public class EventPublicationScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            EventPublicationScheduler.class);

    private final EventPublicationPublisher publisher;
    private final ExecutorService executor;
    private final Semaphore capacity;
    private final String workerId;

    public EventPublicationScheduler(EventPublicationPublisher publisher,
            @Qualifier("eventPublicationExecutor") ExecutorService executor,
            EventPublicationProperties properties,
            @Value("${thaap.event-publication.worker-id:local}") String configuredWorkerId) {
        this.publisher = publisher;
        this.executor = executor;
        this.capacity = new Semaphore(properties.pollBatchSize());
        String safeWorker = configuredWorkerId == null ? "local"
                : configuredWorkerId.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safeWorker.isBlank()) {
            safeWorker = "local";
        }
        safeWorker = safeWorker.substring(0, Math.min(safeWorker.length(), 110));
        this.workerId = safeWorker + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${thaap.event-publication.poll-delay:1s}")
    public void poll() {
        int available = capacity.availablePermits();
        if (available == 0) {
            return;
        }
        try {
            List<PublicationClaim> claims = publisher.claimDue(workerId, available);
            for (PublicationClaim claim : claims) {
                if (!capacity.tryAcquire()) {
                    throw new IllegalStateException(
                            "Publication capacity changed after bounded claim");
                }
                executor.execute(() -> publish(claim));
            }
            publisher.refreshBacklogMetrics();
        } catch (RuntimeException exception) {
            LOGGER.error("Event publication poll failed; component=event_publisher cause={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void publish(PublicationClaim claim) {
        try {
            publisher.publish(claim);
        } catch (RuntimeException exception) {
            LOGGER.error("Event publication attempt failed; component=event_publisher cause={}",
                    exception.getClass().getSimpleName());
        } finally {
            capacity.release();
        }
    }
}
