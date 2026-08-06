package com.thaap.payments.projection.rebuild;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ProjectionRebuildStore {

    public static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ProjectionRebuildStore(JdbcTemplate jdbcTemplate, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ProjectionRebuildLease claim(UUID rebuildId, String workerId) {
        if (rebuildId == null || workerId == null || workerId.isBlank()
                || workerId.length() > 128) {
            throw new IllegalArgumentException("Rebuild and bounded worker identity are required");
        }
        return transaction.execute(status -> claimInTransaction(rebuildId, workerId));
    }

    public List<UUID> recoverable(int limit) {
        Instant now = now();
        return jdbcTemplate.query("""
                SELECT id FROM projection_rebuilds
                WHERE lifecycle_status = 'QUEUED'
                   OR (lifecycle_status = 'RUNNING' AND lease_expires_at <= ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                Timestamp.from(now), limit);
    }

    public ProjectionRebuildLease requireCurrentLease(UUID rebuildId, UUID leaseToken,
            long fencingEpoch) {
        List<ProjectionRebuildLease> matches = jdbcTemplate.query("""
                SELECT rebuild.id, rebuild.projection_definition_id,
                       rebuild.projection_generation_id, rebuild.cutoff_manifest_id,
                       definition.projection_name, rebuild.handler_version,
                       rebuild.lease_owner, rebuild.lease_token, rebuild.fencing_epoch,
                       rebuild.processed_input_count, rebuild.expected_input_count,
                       rebuild.last_checkpoint_key, rebuild.lease_expires_at
                FROM projection_rebuilds rebuild
                JOIN projection_definitions definition
                  ON definition.id = rebuild.projection_definition_id
                WHERE rebuild.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapLease(resultSet), rebuildId);
        if (matches.size() != 1) {
            throw new IllegalStateException("Projection rebuild lease is unavailable");
        }
        ProjectionRebuildLease lease = matches.getFirst();
        if (!lease.leaseToken().equals(leaseToken)
                || lease.fencingEpoch() != fencingEpoch
                || !lease.leaseExpiresAt().isAfter(now())) {
            throw new ProjectionRebuildFenceException();
        }
        return lease;
    }

    public void commitChunk(ProjectionRebuildLease lease, int itemCount,
            String checkpointKey) {
        if (itemCount < 1 || itemCount > 100 || checkpointKey == null
                || checkpointKey.length() > 256) {
            throw new IllegalArgumentException("Rebuild chunk evidence is invalid");
        }
        Instant now = now();
        Instant leaseExpiry = now.plus(LEASE_DURATION);
        int changed = jdbcTemplate.update("""
                UPDATE projection_rebuilds
                SET processed_input_count = processed_input_count + ?,
                    last_checkpoint_key = ?, lease_expires_at = ?, updated_at = ?
                WHERE id = ? AND lifecycle_status = 'RUNNING'
                  AND lease_token = ? AND fencing_epoch = ?
                  AND lease_expires_at > ?
                  AND processed_input_count + ? <= expected_input_count
                """, itemCount, checkpointKey, Timestamp.from(leaseExpiry), Timestamp.from(now),
                lease.rebuildId(), lease.leaseToken(), lease.fencingEpoch(), Timestamp.from(now),
                itemCount);
        if (changed != 1) {
            throw new ProjectionRebuildFenceException();
        }
        appendEvent(lease.rebuildId(), ProjectionRebuildEventType.CHUNK_COMMITTED,
                lease.fencingEpoch(), null, null,
                lease.processedInputCount() + itemCount, now);
    }

    public void finishBuild(UUID rebuildId, UUID leaseToken, long fencingEpoch) {
        transaction.executeWithoutResult(status -> {
            ProjectionRebuildLease lease = requireCurrentLease(rebuildId, leaseToken, fencingEpoch);
            if (lease.processedInputCount() != lease.expectedInputCount()) {
                throw new IllegalStateException("Projection rebuild input is incomplete");
            }
            Instant now = now();
            int generationChanged = jdbcTemplate.update("""
                    UPDATE projection_generations
                    SET lifecycle_status = 'CATCHING_UP'
                    WHERE id = ? AND projection_definition_id = ?
                      AND lifecycle_status = 'BUILDING'
                    """, lease.projectionGenerationId(), lease.projectionDefinitionId());
            int rebuildChanged = jdbcTemplate.update("""
                    UPDATE projection_rebuilds
                    SET lifecycle_status = 'CATCHING_UP', updated_at = ?
                    WHERE id = ? AND lifecycle_status = 'RUNNING'
                      AND lease_token = ? AND fencing_epoch = ?
                    """, Timestamp.from(now), rebuildId, leaseToken, fencingEpoch);
            if (generationChanged != 1 || rebuildChanged != 1) {
                throw new ProjectionRebuildFenceException();
            }
            appendEvent(rebuildId, ProjectionRebuildEventType.CATCH_UP_STARTED,
                    fencingEpoch, null, null, lease.processedInputCount(), now);
        });
    }

    public void failIfCurrent(UUID rebuildId, UUID leaseToken, long fencingEpoch,
            String failureCode) {
        transaction.executeWithoutResult(status -> {
            Instant now = now();
            List<ProjectionRebuildLease> matches = jdbcTemplate.query("""
                    SELECT rebuild.id, rebuild.projection_definition_id,
                           rebuild.projection_generation_id, rebuild.cutoff_manifest_id,
                           definition.projection_name, rebuild.handler_version,
                           rebuild.lease_owner, rebuild.lease_token, rebuild.fencing_epoch,
                           rebuild.processed_input_count, rebuild.expected_input_count,
                           rebuild.last_checkpoint_key, rebuild.lease_expires_at
                    FROM projection_rebuilds rebuild
                    JOIN projection_definitions definition
                      ON definition.id = rebuild.projection_definition_id
                    WHERE rebuild.id = ?
                    FOR UPDATE
                    """, (resultSet, rowNumber) -> mapLease(resultSet), rebuildId);
            if (matches.isEmpty()) {
                return;
            }
            ProjectionRebuildLease lease = matches.getFirst();
            if (!lease.leaseToken().equals(leaseToken)
                    || lease.fencingEpoch() != fencingEpoch) {
                return;
            }
            int changed = jdbcTemplate.update("""
                    UPDATE projection_rebuilds
                    SET lifecycle_status = 'FAILED', failure_code = ?,
                        lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                        updated_at = ?, completed_at = ?
                    WHERE id = ? AND lifecycle_status IN ('RUNNING', 'CATCHING_UP')
                      AND lease_token = ? AND fencing_epoch = ?
                    """, failureCode, Timestamp.from(now), Timestamp.from(now), rebuildId,
                    leaseToken, fencingEpoch);
            if (changed == 1) {
                jdbcTemplate.update("""
                        UPDATE projection_generations SET lifecycle_status = 'FAILED'
                        WHERE id = ? AND lifecycle_status IN ('BUILDING', 'CATCHING_UP')
                        """, lease.projectionGenerationId());
                appendEvent(rebuildId, ProjectionRebuildEventType.FAILED, fencingEpoch,
                        null, failureCode, lease.processedInputCount(), now);
            }
        });
    }

    public void appendEvent(UUID rebuildId, ProjectionRebuildEventType eventType,
            long fencingEpoch, UUID actorOperatorId, String safeReasonCode,
            long processedInputCount, Instant occurredAt) {
        Long maximum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(event_sequence), 0)
                FROM projection_rebuild_events WHERE projection_rebuild_id = ?
                """, Long.class, rebuildId);
        long sequence = maximum == null ? 1 : maximum + 1;
        jdbcTemplate.update("""
                INSERT INTO projection_rebuild_events (
                    id, projection_rebuild_id, event_sequence, event_type,
                    fencing_epoch, actor_operator_id, safe_reason_code,
                    processed_input_count, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), rebuildId, sequence, eventType.name(), fencingEpoch,
                actorOperatorId, safeReasonCode, processedInputCount,
                Timestamp.from(occurredAt));
    }

    private ProjectionRebuildLease claimInTransaction(UUID rebuildId, String workerId) {
        List<ProjectionRebuildLease> matches = jdbcTemplate.query("""
                SELECT rebuild.id, rebuild.projection_definition_id,
                       rebuild.projection_generation_id, rebuild.cutoff_manifest_id,
                       definition.projection_name, rebuild.handler_version,
                       rebuild.lease_owner, rebuild.lease_token, rebuild.fencing_epoch,
                       rebuild.processed_input_count, rebuild.expected_input_count,
                       rebuild.last_checkpoint_key, rebuild.lease_expires_at
                FROM projection_rebuilds rebuild
                JOIN projection_definitions definition
                  ON definition.id = rebuild.projection_definition_id
                WHERE rebuild.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapLease(resultSet), rebuildId);
        if (matches.size() != 1) {
            return null;
        }
        ProjectionRebuildLease prior = matches.getFirst();
        String lifecycle = jdbcTemplate.queryForObject(
                "SELECT lifecycle_status FROM projection_rebuilds WHERE id = ?",
                String.class, rebuildId);
        Instant now = now();
        boolean queued = "QUEUED".equals(lifecycle);
        boolean expired = "RUNNING".equals(lifecycle)
                && prior.leaseExpiresAt() != null && !prior.leaseExpiresAt().isAfter(now);
        if (!queued && !expired) {
            return null;
        }
        UUID token = UUID.randomUUID();
        long epoch = prior.fencingEpoch() + 1;
        Instant expiry = now.plus(LEASE_DURATION);
        int changed = jdbcTemplate.update("""
                UPDATE projection_rebuilds
                SET lifecycle_status = 'RUNNING', lease_owner = ?, lease_token = ?,
                    lease_expires_at = ?, fencing_epoch = ?, updated_at = ?
                WHERE id = ? AND fencing_epoch = ?
                """, workerId, token, Timestamp.from(expiry), epoch, Timestamp.from(now),
                rebuildId, prior.fencingEpoch());
        if (changed != 1) {
            return null;
        }
        appendEvent(rebuildId, queued ? ProjectionRebuildEventType.LEASED
                        : ProjectionRebuildEventType.LEASE_RECOVERED,
                epoch, null, expired ? "EXPIRED_LEASE" : null,
                prior.processedInputCount(), now);
        return new ProjectionRebuildLease(rebuildId, prior.projectionDefinitionId(),
                prior.projectionGenerationId(), prior.cutoffManifestId(), prior.projectionName(),
                prior.handlerVersion(), workerId, token, epoch, prior.processedInputCount(),
                prior.expectedInputCount(), prior.lastCheckpointKey(), expiry, expired);
    }

    private ProjectionRebuildLease mapLease(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new ProjectionRebuildLease(resultSet.getObject(1, UUID.class),
                resultSet.getObject(2, UUID.class), resultSet.getObject(3, UUID.class),
                resultSet.getObject(4, UUID.class), resultSet.getString(5),
                resultSet.getString(6), resultSet.getString(7),
                resultSet.getObject(8, UUID.class), resultSet.getLong(9),
                resultSet.getLong(10), resultSet.getLong(11), resultSet.getString(12),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 13),
                // Reading an existing lease is not a claim, so it never reports recovery.
                false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
