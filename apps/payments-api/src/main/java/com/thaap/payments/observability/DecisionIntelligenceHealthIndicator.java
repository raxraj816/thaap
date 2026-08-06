package com.thaap.payments.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("decisionIntelligence")
public class DecisionIntelligenceHealthIndicator implements HealthIndicator {

    private static final String OPENAPI_ARTIFACT =
            "openapi/thaap-public-api-2026-08-01.yaml";

    private final JdbcTemplate jdbcTemplate;
    private final DecisionIntelligenceMetrics metrics;
    private final Clock clock;

    public DecisionIntelligenceHealthIndicator(JdbcTemplate jdbcTemplate,
            DecisionIntelligenceMetrics metrics, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Health health() {
        try {
            Backlog backlog = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*), MIN(created_at)
                    FROM review_bulk_action_requests
                    WHERE lifecycle_status IN ('PENDING', 'RUNNING')
                    """, (result, number) -> new Backlog(result.getLong(1),
                            result.getTimestamp(2) == null ? null
                                    : result.getTimestamp(2).toInstant()));
            Long relationAnchorCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_investigation_relation_anchors", Long.class);
            if (!new ClassPathResource(OPENAPI_ARTIFACT).exists()) {
                return Health.down().withDetail("cause", "OPENAPI_ARTIFACT_UNAVAILABLE").build();
            }
            Instant now = clock.instant();
            Duration oldestAge = backlog == null || backlog.oldestCreatedAt() == null
                    ? Duration.ZERO : Duration.between(backlog.oldestCreatedAt(), now);
            metrics.bulkBacklog(backlog == null ? 0 : backlog.count(), oldestAge);
            return Health.up()
                    .withDetail("bulkBacklog", backlog == null ? 0 : backlog.count())
                    .withDetail("relatedAnchorRows", relationAnchorCount == null ? 0 : relationAnchorCount)
                    .withDetail("openApiArtifact", "AVAILABLE")
                    .build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "SOURCE_DATABASE_UNAVAILABLE").build();
        }
    }

    private record Backlog(long count, Instant oldestCreatedAt) {
    }
}
