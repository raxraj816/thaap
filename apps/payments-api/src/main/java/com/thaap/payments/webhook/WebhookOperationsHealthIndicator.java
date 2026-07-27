package com.thaap.payments.webhook;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("webhookOperations")
public class WebhookOperationsHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public WebhookOperationsHealthIndicator(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Health health() {
        try {
            Integer deadLetters = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM webhook_deliveries WHERE status = 'EXHAUSTED'
                    """, Integer.class);
            Integer expiredPending = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM webhook_endpoint_verifications
                     WHERE lifecycle_status = 'PENDING' AND expires_at <= ?
                    """, Integer.class, Timestamp.from(clock.instant()));
            Integer invalidGeneration = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM webhook_deliveries
                     WHERE delivery_generation < 1 OR generation_attempt_count < 0
                        OR generation_attempt_count > attempt_count
                    """, Integer.class);
            if (expiredPending == null || invalidGeneration == null
                    || expiredPending != 0 || invalidGeneration != 0) {
                return Health.down()
                        .withDetail("cause", "WEBHOOK_OPERATIONS_STATE_INCOMPATIBLE")
                        .withDetail("expiredPendingChallenges", expiredPending)
                        .withDetail("invalidDeliveryGenerations", invalidGeneration)
                        .build();
            }
            return Health.up()
                    .withDetail("deadLetters", deadLetters == null ? 0 : deadLetters)
                    .withDetail("expiredPendingChallenges", 0)
                    .build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "WEBHOOK_OPERATIONS_SCHEMA_UNAVAILABLE").build();
        }
    }
}
