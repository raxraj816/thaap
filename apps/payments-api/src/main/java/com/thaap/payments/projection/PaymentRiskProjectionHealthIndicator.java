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
            // `pending_stream_count` is what separates a broken consumer from a quiet one. It
            // counts this merchant's streams whose head version the active generation has not
            // applied, by comparing `event_stream_heads` against `projection_stream_positions` —
            // both already maintained, so no broker call is needed on a health path.
            List<TenantProjectionCheckpoint> checkpoints = jdbcTemplate.query("""
                    SELECT event_merchant.merchant_id, definition.projection_name,
                           MAX(checkpoint.last_processed_at),
                           (SELECT COUNT(*)
                              FROM event_stream_heads head
                              LEFT JOIN projection_stream_positions position
                                ON position.merchant_id = head.merchant_id
                               AND position.stream_type = head.stream_type
                               AND position.stream_id = head.stream_id
                               AND position.projection_generation_id = generation.id
                             WHERE head.merchant_id = event_merchant.merchant_id
                               AND head.current_version
                                   > COALESCE(position.last_stream_version, 0))
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
                    GROUP BY event_merchant.merchant_id, definition.projection_name,
                             generation.id
                    """, (resultSet, rowNumber) -> new TenantProjectionCheckpoint(
                            resultSet.getObject(1, java.util.UUID.class),
                            resultSet.getString(2),
                            JdbcTime.instant(resultSet, 3),
                            resultSet.getLong(4)));
            ConsumerReadinessState readiness = readinessState(checkpoints,
                    openQuarantine == null ? 0 : openQuarantine);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("activeGenerationCount", active == null ? 0 : active);
            details.put("openQuarantineCount", openQuarantine == null ? 0 : openQuarantine);
            details.put("heldEventCount", held == null ? 0 : held);
            details.put("tenantProjectionCount", checkpoints.size());
            // Published so an operator reading a DOWN can tell a backlog from a broken query
            // without database access, and so a quiet system is visibly quiet rather than silent.
            details.put("pendingStreamCount", checkpoints.stream()
                    .mapToLong(TenantProjectionCheckpoint::pendingStreamCount).sum());
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

    ConsumerReadinessState readinessState(
            List<TenantProjectionCheckpoint> checkpoints, int openQuarantine) {
        if (checkpoints.isEmpty()) {
            return ConsumerReadinessState.EMPTY;
        }
        boolean lagging = false;
        for (TenantProjectionCheckpoint checkpoint : checkpoints) {
            // A tenant that has nothing left to apply is caught up, however long ago that
            // happened. Judging readiness on elapsed time alone conflates "this merchant has not
            // transacted recently" with "the consumer is broken", and the first is the normal
            // state of almost every merchant almost all of the time.
            //
            // That conflation is not theoretical: with readiness wired to a load balancer, every
            // instance leaves rotation roughly five minutes after traffic goes quiet, and the
            // quieter the deployment the more permanent the outage. It also worsens as tenants
            // accumulate, because one idle merchant is enough to fail the whole application.
            //
            // Pending work is therefore the gate, and age only grades work that is actually
            // waiting. A wedged consumer still trips this, because its backlog does not drain.
            if (checkpoint.pendingStreamCount() == 0) {
                continue;
            }
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

    record TenantProjectionCheckpoint(java.util.UUID merchantId,
            String projectionName, Instant lastProcessedAt, long pendingStreamCount) {
    }
}
