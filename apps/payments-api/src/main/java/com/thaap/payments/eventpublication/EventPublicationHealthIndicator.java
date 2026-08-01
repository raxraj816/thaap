package com.thaap.payments.eventpublication;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component("eventPublication")
@ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "true")
public class EventPublicationHealthIndicator implements HealthIndicator {

    private final EventPublicationProperties properties;
    private final EventPublicationStore store;
    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final Clock clock;

    public EventPublicationHealthIndicator(EventPublicationProperties properties,
            EventPublicationStore store, KafkaTemplate<byte[], byte[]> kafkaTemplate,
            KafkaAdmin kafkaAdmin, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaAdmin = kafkaAdmin;
        this.clock = clock;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.down()
                    .withDetail("cause", "EVENT_PUBLICATION_DISABLED")
                    .withDetail("topicClass", "DOMAIN_EVENTS_V1")
                    .build();
        }
        try {
            validateProducerConfiguration();
            validateTopicConfiguration();
            PublicationHealthSnapshot snapshot = store.healthSnapshot();
            long oldestAgeSeconds = snapshot.oldestUnpublishedAt() == null ? 0L
                    : Math.max(0L, Duration.between(snapshot.oldestUnpublishedAt(),
                            clock.instant()).toSeconds());
            Health.Builder builder;
            if (snapshot.blockedCount() > 0) {
                builder = Health.down().withDetail("cause", "PUBLICATION_BLOCKED");
            } else if (oldestAgeSeconds > properties.backlogFailureAge().toSeconds()) {
                builder = Health.down().withDetail("cause", "PUBLICATION_BACKLOG_STALE");
            } else {
                builder = Health.up();
            }
            return builder
                    .withDetail("topicClass", "DOMAIN_EVENTS_V1")
                    .withDetail("pendingCount", snapshot.pendingCount())
                    .withDetail("claimedCount", snapshot.claimedCount())
                    .withDetail("retryingCount", snapshot.retryingCount())
                    .withDetail("blockedCount", snapshot.blockedCount())
                    .withDetail("oldestUnpublishedAgeSeconds", oldestAgeSeconds)
                    .withDetail("lastOutcome", snapshot.lastOutcome() == null
                            ? "NONE" : snapshot.lastOutcome())
                    .build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("cause", "EVENT_PUBLICATION_DEPENDENCY_UNAVAILABLE")
                    .withDetail("topicClass", "DOMAIN_EVENTS_V1")
                    .build();
        }
    }

    private void validateProducerConfiguration() {
        Map<String, Object> configuration = kafkaTemplate.getProducerFactory()
                .getConfigurationProperties();
        requireValue(configuration, ProducerConfig.ACKS_CONFIG, "all");
        requireValue(configuration, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        requireValue(configuration, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        requireValue(configuration, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        requireClass(configuration, ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        requireClass(configuration, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
    }

    private void validateTopicConfiguration() {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = admin.describeTopics(Set.of(properties.topic()))
                    .allTopicNames().get(2, TimeUnit.SECONDS).get(properties.topic());
            if (description == null
                    || description.partitions().size() != properties.partitions()) {
                throw new IllegalStateException("Domain-event topic partition drift");
            }
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC,
                    properties.topic());
            Config config = admin.describeConfigs(Set.of(resource)).all()
                    .get(2, TimeUnit.SECONDS).get(resource);
            String retention = config == null || config.get(TopicConfig.RETENTION_MS_CONFIG) == null
                    ? null : config.get(TopicConfig.RETENTION_MS_CONFIG).value();
            if (!Long.toString(properties.retention().toMillis()).equals(retention)) {
                throw new IllegalStateException("Domain-event topic retention drift");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka topology validation was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka topology validation failed", exception);
        }
    }

    private void requireValue(Map<String, Object> configuration, String name,
            String expected) {
        Object value = configuration.get(name);
        if (value == null || !expected.equalsIgnoreCase(String.valueOf(value))) {
            throw new IllegalStateException("Kafka producer configuration drift: " + name);
        }
    }

    private void requireClass(Map<String, Object> configuration, String name,
            String expectedClassName) {
        Object value = configuration.get(name);
        String actual = value instanceof Class<?> type ? type.getName() : String.valueOf(value);
        if (!expectedClassName.equals(actual)) {
            throw new IllegalStateException("Kafka producer serializer drift: " + name);
        }
    }
}
