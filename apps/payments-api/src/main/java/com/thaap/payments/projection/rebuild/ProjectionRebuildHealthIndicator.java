package com.thaap.payments.projection.rebuild;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("projectionRebuild")
public class ProjectionRebuildHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean workerEnabled;

    public ProjectionRebuildHealthIndicator(JdbcTemplate jdbcTemplate, Clock clock,
            @Value("${thaap.projection-rebuild.worker-enabled:true}") boolean workerEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.workerEnabled = workerEnabled;
    }

    @Override
    public Health health() {
        if (!workerEnabled) {
            return Health.outOfService().withDetail("state", "WORKER_DISABLED").build();
        }
        try {
            Integer active = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM projection_rebuilds
                    WHERE lifecycle_status IN ('QUEUED', 'RUNNING', 'CATCHING_UP')
                    """, Integer.class);
            Integer expired = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM projection_rebuilds
                    WHERE lifecycle_status IN ('RUNNING', 'CATCHING_UP')
                      AND lease_expires_at <= ?
                    """, Integer.class, Timestamp.from(clock.instant()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("activeRebuildCount", active == null ? 0 : active);
            details.put("expiredLeaseCount", expired == null ? 0 : expired);
            details.put("leaseDurationSeconds", ProjectionRebuildStore.LEASE_DURATION.toSeconds());
            details.put("chunkSize", ProjectionRebuildBatchConfiguration.CHUNK_SIZE);
            if (expired != null && expired > 0) {
                return Health.down().withDetails(details).build();
            }
            return Health.up().withDetails(details).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("state", "UNAVAILABLE")
                    .withDetail("failureType", exception.getClass().getSimpleName()).build();
        }
    }
}
