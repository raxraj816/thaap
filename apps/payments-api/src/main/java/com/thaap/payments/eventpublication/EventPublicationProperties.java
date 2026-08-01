package com.thaap.payments.eventpublication;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("thaap.event-publication")
public record EventPublicationProperties(
        boolean enabled,
        String topic,
        int partitions,
        Duration retention,
        int recordMaxBytes,
        int pollBatchSize,
        int tenantBudget,
        int streamBudget,
        Duration leaseDuration,
        int retryLimit,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Duration pollDelay,
        Duration backlogFailureAge) {

    public static final String TOPIC = "thaap.domain-events.v1";
    public static final int PARTITIONS = 6;
    public static final Duration RETENTION = Duration.ofDays(7);
    public static final int RECORD_MAX_BYTES = 65_536;
    public static final int POLL_BATCH_SIZE = 100;
    public static final int TENANT_BUDGET = 20;
    public static final int STREAM_BUDGET = 10;
    public static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    public static final int RETRY_LIMIT = 10;

    public EventPublicationProperties {
        requireEqual("topic", TOPIC, topic);
        requireEqual("partitions", PARTITIONS, partitions);
        requireEqual("retention", RETENTION, retention);
        requireEqual("record-max-bytes", RECORD_MAX_BYTES, recordMaxBytes);
        requireEqual("poll-batch-size", POLL_BATCH_SIZE, pollBatchSize);
        requireEqual("tenant-budget", TENANT_BUDGET, tenantBudget);
        requireEqual("stream-budget", STREAM_BUDGET, streamBudget);
        requireEqual("lease-duration", LEASE_DURATION, leaseDuration);
        requireEqual("retry-limit", RETRY_LIMIT, retryLimit);
        if (retryBaseDelay == null || retryBaseDelay.isNegative()
                || retryBaseDelay.isZero()) {
            throw new IllegalArgumentException(
                    "thaap.event-publication.retry-base-delay must be positive");
        }
        if (retryMaxDelay == null || retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException(
                    "thaap.event-publication.retry-max-delay must be at least the base delay");
        }
        if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()) {
            throw new IllegalArgumentException(
                    "thaap.event-publication.poll-delay must be positive");
        }
        if (backlogFailureAge == null || backlogFailureAge.isNegative()
                || backlogFailureAge.isZero()) {
            throw new IllegalArgumentException(
                    "thaap.event-publication.backlog-failure-age must be positive");
        }
    }

    private static void requireEqual(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "thaap.event-publication." + name + " must remain " + expected);
        }
    }
}
