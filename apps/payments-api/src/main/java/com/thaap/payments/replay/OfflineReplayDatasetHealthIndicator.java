package com.thaap.payments.replay;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("offlineReplayDataset")
public class OfflineReplayDatasetHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean workerEnabled;
    private final boolean creationEnabled;
    private final Duration staleAge;

    public OfflineReplayDatasetHealthIndicator(JdbcTemplate jdbcTemplate, Clock clock,
            @Value("${thaap.replay-datasets.worker-enabled:true}") boolean workerEnabled,
            @Value("${thaap.replay-datasets.creation-enabled:true}") boolean creationEnabled,
            @Value("${thaap.replay-datasets.stale-age:5m}") Duration staleAge) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.workerEnabled = workerEnabled;
        this.creationEnabled = creationEnabled;
        this.staleAge = staleAge;
    }

    @Override
    public Health health() {
        if (creationEnabled && !workerEnabled) {
            return Health.down().withDetail("cause", "CREATION_ENABLED_WITHOUT_WORKER").build();
        }
        try {
            List<String> missing = jdbcTemplate.queryForList("""
                    SELECT required.table_name
                      FROM (VALUES ('replay_dataset_manifests'),
                                   ('replay_dataset_results'),
                                   ('replay_dataset_chunks'),
                                   ('replay_dataset_item_evidence'),
                                   ('replay_dataset_compatibility_evidence')) required(table_name)
                     WHERE NOT EXISTS (
                           SELECT 1 FROM information_schema.tables actual
                            WHERE LOWER(actual.table_name) = required.table_name)
                    """, String.class);
            if (!missing.isEmpty()) {
                return Health.down().withDetail("cause", "REPLAY_DATASET_SCHEMA_INCOMPLETE").build();
            }
            Long stale = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM replay_dataset_manifests
                     WHERE lifecycle_status IN ('QUEUED', 'RUNNING')
                       AND updated_at <= ?
                    """, Long.class, Timestamp.from(clock.instant().minus(staleAge)));
            if (workerEnabled && stale != null && stale > 0) {
                return Health.down().withDetail("cause", "REPLAY_DATASET_CHECKPOINT_STALE")
                        .withDetail("staleCount", stale).build();
            }
            return Health.up().withDetail("worker", workerEnabled ? "ENABLED" : "DISABLED")
                    .withDetail("creation", creationEnabled ? "ENABLED" : "DISABLED").build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "REPLAY_DATASET_SCHEMA_UNAVAILABLE").build();
        }
    }
}
