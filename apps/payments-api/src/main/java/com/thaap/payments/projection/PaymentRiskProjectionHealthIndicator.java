package com.thaap.payments.projection;

import com.thaap.payments.persistence.JdbcTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("paymentRiskProjection")
public class PaymentRiskProjectionHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;

    public PaymentRiskProjectionHealthIndicator(JdbcTemplate jdbcTemplate, Clock clock,
            @Value("${thaap.projection-consumer.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.status(Status.OUT_OF_SERVICE)
                    .withDetail("consumerState", "DISABLED")
                    .withDetail("required", true).build();
        }
        try {
            Integer active = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM projection_definitions definition
                    JOIN projection_generations generation
                      ON generation.id = definition.active_generation_id
                    WHERE definition.projection_name IN ('payment-risk-v1', 'merchant-risk-v1')
                      AND definition.lifecycle_status = 'ENABLED'
                      AND generation.lifecycle_status = 'ACTIVE'
                      AND generation.handler_version = 'v1'
                    """, Integer.class);
            Integer openQuarantine = jdbcTemplate.queryForObject("""
                    SELECT
                        (SELECT COUNT(*) FROM consumer_quarantine
                         WHERE consumer_name = 'payment-risk-projection-v1'
                           AND lifecycle_status IN ('OPEN', 'RETRY_REQUESTED'))
                        +
                        (SELECT COUNT(*) FROM consumer_transport_quarantine
                         WHERE consumer_name = 'payment-risk-projection-v1'
                           AND lifecycle_status = 'OPEN')
                    """, Integer.class);
            Integer held = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM consumer_inbox
                    WHERE consumer_name = 'payment-risk-projection-v1'
                      AND lifecycle_status = 'HELD'
                    """, Integer.class);
            List<TenantProjectionCheckpoint> checkpoints = jdbcTemplate.query("""
                    SELECT event_merchant.merchant_id, definition.projection_name,
                           MAX(checkpoint.last_processed_at)
                    FROM (SELECT DISTINCT merchant_id FROM domain_events) event_merchant
                    CROSS JOIN projection_definitions definition
                    JOIN projection_generations generation
                      ON generation.id = definition.active_generation_id
                     AND generation.projection_definition_id = definition.id
                    LEFT JOIN projection_checkpoints checkpoint
                      ON checkpoint.merchant_id = event_merchant.merchant_id
                     AND checkpoint.projection_generation_id = generation.id
                     AND checkpoint.consumer_name = 'payment-risk-projection-v1'
                    WHERE definition.projection_name IN
                        ('payment-risk-v1', 'merchant-risk-v1')
                      AND definition.lifecycle_status = 'ENABLED'
                      AND generation.lifecycle_status = 'ACTIVE'
                      AND generation.handler_version = 'v1'
                    GROUP BY event_merchant.merchant_id, definition.projection_name
                    """, (resultSet, rowNumber) -> new TenantProjectionCheckpoint(
                            resultSet.getObject(1, java.util.UUID.class),
                            resultSet.getString(2),
                            JdbcTime.instant(resultSet, 3)));
            ConsumerReadinessState readiness = readinessState(checkpoints,
                    openQuarantine == null ? 0 : openQuarantine);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("activeGenerationCount", active == null ? 0 : active);
            details.put("openQuarantineCount", openQuarantine == null ? 0 : openQuarantine);
            details.put("heldEventCount", held == null ? 0 : held);
            details.put("tenantProjectionCount", checkpoints.size());
            details.put("consumerState", readiness.name());
            if (active == null || active != 2) {
                details.put("consumerState", ConsumerReadinessState.INCOMPATIBLE.name());
                return Health.down().withDetails(details).build();
            }
            if (readiness == ConsumerReadinessState.UNAVAILABLE
                    || readiness == ConsumerReadinessState.STALE) {
                return Health.down().withDetails(details).build();
            }
            return Health.up().withDetails(details).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("state", "UNAVAILABLE")
                    .withDetail("failureType", exception.getClass().getSimpleName()).build();
        }
    }

    private ConsumerReadinessState readinessState(
            List<TenantProjectionCheckpoint> checkpoints, int openQuarantine) {
        if (checkpoints.isEmpty()) {
            return ConsumerReadinessState.EMPTY;
        }
        boolean lagging = false;
        for (TenantProjectionCheckpoint checkpoint : checkpoints) {
            if (checkpoint.lastProcessedAt() == null) {
                return ConsumerReadinessState.UNAVAILABLE;
            }
            ConsumerReadinessState ageState = checkpointAgeState(
                    clock.instant(), checkpoint.lastProcessedAt());
            if (ageState == ConsumerReadinessState.STALE) {
                return ConsumerReadinessState.STALE;
            }
            if (ageState == ConsumerReadinessState.LAGGING) {
                lagging = true;
            }
        }
        if (openQuarantine > 0) {
            return ConsumerReadinessState.PARTIAL;
        }
        return lagging ? ConsumerReadinessState.LAGGING : ConsumerReadinessState.FRESH;
    }

    static ConsumerReadinessState checkpointAgeState(Instant now, Instant lastProcessedAt) {
        if (lastProcessedAt == null) {
            return ConsumerReadinessState.UNAVAILABLE;
        }
        Duration age = Duration.between(lastProcessedAt, now);
        if (age.isNegative()) {
            return ConsumerReadinessState.UNAVAILABLE;
        }
        if (age.compareTo(Duration.ofSeconds(60)) <= 0) {
            return ConsumerReadinessState.FRESH;
        }
        if (age.compareTo(Duration.ofMinutes(5)) <= 0) {
            return ConsumerReadinessState.LAGGING;
        }
        return ConsumerReadinessState.STALE;
    }

    enum ConsumerReadinessState {
        FRESH,
        LAGGING,
        STALE,
        EMPTY,
        PARTIAL,
        UNAVAILABLE,
        INCOMPATIBLE
    }

    private record TenantProjectionCheckpoint(java.util.UUID merchantId,
            String projectionName, Instant lastProcessedAt) {
    }
}
