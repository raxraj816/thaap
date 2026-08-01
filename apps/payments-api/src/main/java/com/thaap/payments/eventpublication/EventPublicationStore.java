package com.thaap.payments.eventpublication;

import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.event.EventTimePrecision;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventPublicationStore {

    private static final String CANDIDATE_QUERY = """
            WITH due AS (
                SELECT p.id, p.merchant_id, p.domain_event_id,
                       p.lifecycle_status, p.retry_count, p.total_attempt_count,
                       p.next_attempt_at, p.lease_owner, p.lease_token,
                       p.lease_expires_at, p.last_outcome, p.last_error_code,
                       p.created_at, p.updated_at,
                       e.stream_type, e.stream_id, e.stream_version,
                       e.event_version, e.event_record_bytes, e.event_record_sha256,
                       ROW_NUMBER() OVER (
                           PARTITION BY p.merchant_id
                           ORDER BY p.created_at, p.id) AS tenant_rank,
                       ROW_NUMBER() OVER (
                           PARTITION BY p.merchant_id, e.stream_type, e.stream_id
                           ORDER BY e.stream_version, p.id) AS stream_rank
                  FROM event_publications p
                  JOIN domain_events e
                    ON e.merchant_id = p.merchant_id
                   AND e.id = p.domain_event_id
                 WHERE ((p.lifecycle_status IN ('PENDING', 'RETRY_SCHEDULED')
                            AND p.next_attempt_at <= ?)
                        OR (p.lifecycle_status = 'CLAIMED'
                            AND p.lease_expires_at <= ?))
                   AND NOT EXISTS (
                       SELECT 1
                         FROM domain_events earlier_event
                         JOIN event_publications earlier_publication
                           ON earlier_publication.merchant_id = earlier_event.merchant_id
                          AND earlier_publication.domain_event_id = earlier_event.id
                        WHERE earlier_event.merchant_id = e.merchant_id
                          AND earlier_event.stream_type = e.stream_type
                          AND earlier_event.stream_id = e.stream_id
                          AND earlier_event.stream_version < e.stream_version
                          AND earlier_publication.lifecycle_status <> 'PUBLISHED')
            ), budgeted AS (
                SELECT id
                  FROM due
                 WHERE tenant_rank <= ? AND stream_rank <= ?
                 ORDER BY created_at, id
                 FETCH FIRST ? ROWS ONLY
            )
            SELECT p.id, p.merchant_id, p.domain_event_id,
                   p.lifecycle_status, p.retry_count, p.total_attempt_count,
                   p.next_attempt_at, p.lease_owner, p.lease_token,
                   p.lease_expires_at, p.last_outcome, p.last_error_code,
                   p.created_at, p.updated_at,
                   e.stream_type, e.stream_id, e.stream_version,
                   e.event_version, e.event_record_bytes, e.event_record_sha256
              FROM event_publications p
              JOIN domain_events e
                ON e.merchant_id = p.merchant_id
               AND e.id = p.domain_event_id
             WHERE p.id IN (SELECT id FROM budgeted)
             ORDER BY p.created_at, p.id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EventPublicationProperties properties;

    public EventPublicationStore(JdbcTemplate jdbcTemplate,
            EventPublicationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<PublicationClaim> claimDue(Instant requestedNow, String workerId,
            int requestedLimit) {
        requireWorkerId(workerId);
        int limit = Math.min(Math.max(requestedLimit, 0), properties.pollBatchSize());
        if (limit == 0) {
            return List.of();
        }
        Instant now = EventTimePrecision.toPostgreSqlMicroseconds(requestedNow);
        String databaseProduct = jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
        String lockingClause = "PostgreSQL".equals(databaseProduct)
                ? " FOR UPDATE OF p SKIP LOCKED"
                : " FOR UPDATE SKIP LOCKED";
        List<PublicationRow> rows = jdbcTemplate.query(CANDIDATE_QUERY + lockingClause,
                (resultSet, rowNumber) -> new PublicationRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("merchant_id", UUID.class),
                        resultSet.getObject("domain_event_id", UUID.class),
                        resultSet.getString("lifecycle_status"),
                        resultSet.getInt("retry_count"),
                        resultSet.getInt("total_attempt_count"),
                        resultSet.getTimestamp("next_attempt_at").toInstant(),
                        resultSet.getString("lease_owner"),
                        resultSet.getObject("lease_token", UUID.class),
                        instant(resultSet.getTimestamp("lease_expires_at")),
                        resultSet.getString("last_outcome"),
                        resultSet.getString("last_error_code"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant(),
                        DomainStreamType.valueOf(resultSet.getString("stream_type")),
                        resultSet.getObject("stream_id", UUID.class),
                        resultSet.getLong("stream_version"),
                        resultSet.getInt("event_version"),
                        resultSet.getBytes("event_record_bytes"),
                        resultSet.getString("event_record_sha256")),
                Timestamp.from(now), Timestamp.from(now), properties.tenantBudget(),
                properties.streamBudget(), limit);
        List<PublicationClaim> claims = new ArrayList<>(rows.size());
        for (PublicationRow row : rows) {
            claims.add(claim(row, workerId, now));
        }
        return List.copyOf(claims);
    }

    private PublicationClaim claim(PublicationRow row, String workerId, Instant now) {
        boolean leaseRecovered = "CLAIMED".equals(row.lifecycleStatus());
        int totalAttemptCount = row.totalAttemptCount();
        String lastOutcome = row.lastOutcome();
        String lastErrorCode = row.lastErrorCode();
        if (leaseRecovered) {
            if (row.leaseToken() == null || row.leaseExpiresAt() == null
                    || row.leaseExpiresAt().isAfter(now)) {
                throw new IllegalStateException(
                        "Only an expired, fenced publication claim can be recovered");
            }
            totalAttemptCount++;
            lastOutcome = PublicationOutcome.LEASE_RECOVERED.name();
            lastErrorCode = PublicationSafeErrorCode.LEASE_EXPIRED.name();
        }
        UUID fencingToken = UUID.randomUUID();
        Instant leaseExpiresAt = EventTimePrecision.toPostgreSqlMicroseconds(
                now.plus(properties.leaseDuration()));
        int changed = jdbcTemplate.update("""
                UPDATE event_publications
                   SET lifecycle_status = 'CLAIMED',
                       total_attempt_count = ?,
                       lease_owner = ?, lease_token = ?, lease_expires_at = ?,
                       last_outcome = ?, last_error_code = ?, updated_at = ?
                 WHERE merchant_id = ? AND id = ?
                """, totalAttemptCount, workerId, fencingToken,
                Timestamp.from(leaseExpiresAt), lastOutcome, lastErrorCode,
                Timestamp.from(now), row.merchantId(), row.publicationId());
        if (changed != 1) {
            throw new IllegalStateException("Publication claim update lost its locked row");
        }
        if (leaseRecovered) {
            appendAttempt(row, row.leaseToken(), totalAttemptCount,
                    PublicationOutcome.LEASE_RECOVERED,
                    PublicationSafeErrorCode.LEASE_EXPIRED,
                    null, null, workerId, row.updatedAt(), now);
        }
        return new PublicationClaim(row.publicationId(), row.merchantId(),
                row.domainEventId(), row.streamType(), row.streamId(),
                row.streamVersion(), row.eventVersion(), row.eventRecordBytes(),
                row.eventRecordSha256(), fencingToken, workerId, now,
                leaseExpiresAt, leaseRecovered);
    }

    public PublicationFinalization finalizeClaim(PublicationClaim claim,
            PublicationSendResult result, Instant requestedCompletedAt) {
        Instant completedAt = EventTimePrecision.toPostgreSqlMicroseconds(
                requestedCompletedAt);
        LockedPublication publication = findForUpdate(claim.publicationId());
        if (!"CLAIMED".equals(publication.lifecycleStatus())
                || publication.leaseToken() == null
                || !publication.leaseToken().equals(claim.fencingToken())) {
            return PublicationFinalization.STALE_FENCING_TOKEN;
        }
        if (!publication.leaseExpiresAt().isAfter(completedAt)) {
            return PublicationFinalization.EXPIRED_LEASE;
        }
        if (result.outcome() == PublicationOutcome.LEASE_RECOVERED) {
            throw new IllegalArgumentException(
                    "Lease recovery is recorded only by the claimant");
        }

        int nextAttemptNumber = publication.totalAttemptCount() + 1;
        int retryCount = publication.retryCount();
        String status;
        Instant nextAttemptAt = publication.nextAttemptAt();
        Instant acknowledgedAt = null;
        Instant blockedAt = null;
        if (result.outcome() == PublicationOutcome.ACKNOWLEDGED) {
            status = "PUBLISHED";
            acknowledgedAt = completedAt;
        } else if (result.outcome() == PublicationOutcome.PERMANENT_FAILURE
                || retryCount >= properties.retryLimit()) {
            status = "BLOCKED";
            blockedAt = completedAt;
        } else {
            status = "RETRY_SCHEDULED";
            retryCount++;
            nextAttemptAt = EventTimePrecision.toPostgreSqlMicroseconds(
                    completedAt.plus(retryDelay(retryCount)));
        }

        int changed = jdbcTemplate.update("""
                UPDATE event_publications
                   SET lifecycle_status = ?, retry_count = ?, total_attempt_count = ?,
                       next_attempt_at = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, last_outcome = ?, last_error_code = ?,
                       published_partition = ?, published_offset = ?, acknowledged_at = ?,
                       blocked_at = ?, updated_at = ?
                 WHERE id = ? AND merchant_id = ? AND lease_token = ?
                """, status, retryCount, nextAttemptNumber,
                Timestamp.from(nextAttemptAt), result.outcome().name(),
                result.safeErrorCode() == null ? null : result.safeErrorCode().name(),
                result.brokerPartition(), result.brokerOffset(), timestamp(acknowledgedAt),
                timestamp(blockedAt), Timestamp.from(completedAt),
                claim.publicationId(), claim.merchantId(), claim.fencingToken());
        if (changed != 1) {
            return PublicationFinalization.STALE_FENCING_TOKEN;
        }
        appendAttempt(publication, claim, nextAttemptNumber, result, completedAt);
        return PublicationFinalization.APPLIED;
    }

    public PublicationHealthSnapshot healthSnapshot() {
        return jdbcTemplate.queryForObject("""
                SELECT
                    SUM(CASE WHEN lifecycle_status = 'PENDING' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN lifecycle_status = 'CLAIMED' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN lifecycle_status = 'RETRY_SCHEDULED' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN lifecycle_status = 'BLOCKED' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN lifecycle_status = 'PUBLISHED' THEN 1 ELSE 0 END),
                    MIN(CASE WHEN lifecycle_status <> 'PUBLISHED' THEN created_at END),
                    MAX(acknowledged_at),
                    (SELECT latest.last_outcome
                       FROM event_publications latest
                      WHERE latest.last_outcome IS NOT NULL
                      ORDER BY latest.updated_at DESC, latest.id DESC
                      FETCH FIRST 1 ROW ONLY)
                  FROM event_publications
                """, (resultSet, rowNumber) -> new PublicationHealthSnapshot(
                        resultSet.getLong(1), resultSet.getLong(2),
                        resultSet.getLong(3), resultSet.getLong(4),
                        resultSet.getLong(5), instant(resultSet.getTimestamp(6)),
                        instant(resultSet.getTimestamp(7)), resultSet.getString(8)));
    }

    public boolean reopenBlocked(UUID publicationId, Instant requestedNow) {
        Instant now = EventTimePrecision.toPostgreSqlMicroseconds(requestedNow);
        return jdbcTemplate.update("""
                UPDATE event_publications
                   SET lifecycle_status = 'PENDING', retry_count = 0,
                       next_attempt_at = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, blocked_at = NULL, updated_at = ?
                 WHERE id = ? AND lifecycle_status = 'BLOCKED'
                """, Timestamp.from(now), Timestamp.from(now), publicationId) == 1;
    }

    public String lifecycleStatusForUpdate(UUID publicationId) {
        List<String> statuses = jdbcTemplate.query(
                "SELECT lifecycle_status FROM event_publications WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getString(1), publicationId);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private LockedPublication findForUpdate(UUID publicationId) {
        List<LockedPublication> rows = jdbcTemplate.query("""
                SELECT id, merchant_id, domain_event_id, lifecycle_status,
                       retry_count, total_attempt_count, next_attempt_at,
                       lease_token, lease_expires_at
                  FROM event_publications
                 WHERE id = ?
                 FOR UPDATE
                """, (resultSet, rowNumber) -> new LockedPublication(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("merchant_id", UUID.class),
                        resultSet.getObject("domain_event_id", UUID.class),
                        resultSet.getString("lifecycle_status"),
                        resultSet.getInt("retry_count"),
                        resultSet.getInt("total_attempt_count"),
                        resultSet.getTimestamp("next_attempt_at").toInstant(),
                        resultSet.getObject("lease_token", UUID.class),
                        instant(resultSet.getTimestamp("lease_expires_at"))), publicationId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Claimed publication no longer exists");
        }
        return rows.getFirst();
    }

    private void appendAttempt(PublicationRow row, UUID fencingToken,
            int attemptNumber, PublicationOutcome outcome,
            PublicationSafeErrorCode safeErrorCode, Integer brokerPartition,
            Long brokerOffset, String workerId, Instant startedAt,
            Instant completedAt) {
        insertAttempt(row.merchantId(), row.publicationId(), row.domainEventId(),
                attemptNumber, fencingToken, outcome, safeErrorCode,
                brokerPartition, brokerOffset, workerId, startedAt, completedAt);
    }

    private void appendAttempt(LockedPublication publication, PublicationClaim claim,
            int attemptNumber, PublicationSendResult result, Instant completedAt) {
        insertAttempt(publication.merchantId(), publication.publicationId(),
                publication.domainEventId(), attemptNumber, claim.fencingToken(),
                result.outcome(), result.safeErrorCode(), result.brokerPartition(),
                result.brokerOffset(), claim.workerId(), claim.claimedAt(), completedAt);
    }

    private void insertAttempt(UUID merchantId, UUID publicationId, UUID domainEventId,
            int attemptNumber, UUID fencingToken, PublicationOutcome outcome,
            PublicationSafeErrorCode safeErrorCode, Integer brokerPartition,
            Long brokerOffset, String workerId, Instant startedAt,
            Instant completedAt) {
        jdbcTemplate.update("""
                INSERT INTO event_publication_attempts (
                    id, merchant_id, publication_id, domain_event_id,
                    attempt_number, fencing_token, outcome, safe_error_code,
                    topic_name, broker_partition, broker_offset, worker_id,
                    started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), merchantId, publicationId, domainEventId,
                attemptNumber, fencingToken, outcome.name(),
                safeErrorCode == null ? null : safeErrorCode.name(),
                properties.topic(), brokerPartition, brokerOffset, workerId,
                Timestamp.from(startedAt), Timestamp.from(completedAt));
    }

    private Duration retryDelay(int retryNumber) {
        long multiplier = 1L << Math.min(retryNumber - 1, 20);
        Duration delay;
        try {
            delay = properties.retryBaseDelay().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            delay = properties.retryMaxDelay();
        }
        return delay.compareTo(properties.retryMaxDelay()) > 0
                ? properties.retryMaxDelay() : delay;
    }

    private void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128
                || !workerId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "Publication worker id must be a safe identifier of at most 128 characters");
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record PublicationRow(UUID publicationId, UUID merchantId,
            UUID domainEventId, String lifecycleStatus, int retryCount,
            int totalAttemptCount, Instant nextAttemptAt, String leaseOwner,
            UUID leaseToken, Instant leaseExpiresAt, String lastOutcome,
            String lastErrorCode, Instant createdAt, Instant updatedAt,
            DomainStreamType streamType, UUID streamId, long streamVersion,
            int eventVersion, byte[] eventRecordBytes,
            String eventRecordSha256) {
    }

    private record LockedPublication(UUID publicationId, UUID merchantId,
            UUID domainEventId, String lifecycleStatus, int retryCount,
            int totalAttemptCount, Instant nextAttemptAt, UUID leaseToken,
            Instant leaseExpiresAt) {
    }
}
