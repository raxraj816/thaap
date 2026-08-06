package com.thaap.payments.bulk;

import com.thaap.payments.persistence.JdbcTime;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReviewBulkActionStore {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ReviewBulkActionStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void insertRequest(UUID requestId, UUID merchantId, UUID actorId,
            ReviewBulkActionCode action, UUID targetOperatorId, String idempotencyIdentity,
            String requestDigest, int itemCount, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO review_bulk_action_requests
                    (id, merchant_id, initiating_operator_id, action_code,
                     target_operator_id, idempotency_identity_hmac, request_digest,
                     item_count, lifecycle_status, succeeded_count, failed_count,
                     lease_epoch, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, 0, ?, ?)
                """, requestId, merchantId, actorId, action.name(), targetOperatorId,
                idempotencyIdentity, requestDigest, itemCount,
                Timestamp.from(now), Timestamp.from(now));
    }

    public void insertItem(UUID itemId, UUID merchantId, UUID requestId, UUID reviewCaseId,
            long expectedVersion, int ordinal, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO review_bulk_action_items
                    (id, merchant_id, bulk_action_request_id, manual_review_case_id,
                     expected_assignment_version, ordinal, lifecycle_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, itemId, merchantId, requestId, reviewCaseId, expectedVersion,
                ordinal, Timestamp.from(now));
    }

    public RequestView findByIdempotency(UUID merchantId, UUID actorId, String identity) {
        List<RequestView> rows = jdbcTemplate.query("""
                SELECT id, merchant_id, initiating_operator_id, action_code,
                       target_operator_id, request_digest, item_count, lifecycle_status,
                       succeeded_count, failed_count, lease_epoch, created_at,
                       started_at, completed_at, updated_at
                FROM review_bulk_action_requests
                WHERE merchant_id = ? AND initiating_operator_id = ?
                  AND idempotency_identity_hmac = ?
                """, (result, number) -> requestView(result), merchantId, actorId, identity);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RequestView find(UUID merchantId, UUID requestId) {
        List<RequestView> rows = jdbcTemplate.query("""
                SELECT id, merchant_id, initiating_operator_id, action_code,
                       target_operator_id, request_digest, item_count, lifecycle_status,
                       succeeded_count, failed_count, lease_epoch, created_at,
                       started_at, completed_at, updated_at
                FROM review_bulk_action_requests
                WHERE merchant_id = ? AND id = ?
                """, (result, number) -> requestView(result), merchantId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<ItemView> items(UUID merchantId, UUID requestId, int afterOrdinal,
            int limit) {
        return jdbcTemplate.query("""
                SELECT id, manual_review_case_id, expected_assignment_version, ordinal,
                       lifecycle_status, failure_code, observed_assignment_version,
                       final_assignment_version, claimed_at, completed_at
                FROM review_bulk_action_items
                WHERE merchant_id = ? AND bulk_action_request_id = ? AND ordinal > ?
                ORDER BY ordinal, id LIMIT ?
                """, (result, number) -> new ItemView(result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class), result.getLong(3), result.getInt(4),
                        result.getString(5), result.getString(6), nullableLong(result.getObject(7)),
                        nullableLong(result.getObject(8)), JdbcTime.instant(result, 9),
                        JdbcTime.instant(result, 10)), merchantId, requestId, afterOrdinal, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lease claim(UUID requestId, String workerId) {
        List<ClaimCandidate> candidates = jdbcTemplate.query("""
                SELECT id, merchant_id, lease_epoch, lifecycle_status, lease_expires_at
                FROM review_bulk_action_requests
                WHERE id = ? FOR UPDATE
                """, (result, number) -> new ClaimCandidate(result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class), result.getLong(3), result.getString(4),
                        JdbcTime.instant(result, 5)), requestId);
        if (candidates.isEmpty()) return null;
        ClaimCandidate candidate = candidates.getFirst();
        Instant now = clock.instant();
        if (List.of("COMPLETED", "PARTIAL", "FAILED").contains(candidate.status())
                || ("RUNNING".equals(candidate.status()) && candidate.leaseExpiresAt() != null
                    && candidate.leaseExpiresAt().isAfter(now))) {
            return null;
        }
        UUID token = UUID.randomUUID();
        long epoch = candidate.epoch() + 1;
        Instant expiresAt = now.plus(LEASE_DURATION);
        int updated = jdbcTemplate.update("""
                UPDATE review_bulk_action_requests
                SET lifecycle_status = 'RUNNING', lease_token = ?, lease_epoch = ?,
                    lease_expires_at = ?, started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE id = ? AND merchant_id = ? AND lease_epoch = ?
                """, token, epoch, Timestamp.from(expiresAt), Timestamp.from(now),
                Timestamp.from(now), requestId, candidate.merchantId(), candidate.epoch());
        return updated == 1 ? new Lease(requestId, candidate.merchantId(), token, epoch, workerId)
                : null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimedItem claimNext(Lease lease) {
        List<ClaimedItem> candidates = jdbcTemplate.query("""
                SELECT item.id, item.merchant_id, item.bulk_action_request_id,
                       item.manual_review_case_id, item.expected_assignment_version,
                       item.ordinal, request.initiating_operator_id, request.action_code,
                       request.target_operator_id
                FROM review_bulk_action_items item
                JOIN review_bulk_action_requests request
                  ON request.merchant_id = item.merchant_id
                 AND request.id = item.bulk_action_request_id
                WHERE item.merchant_id = ? AND item.bulk_action_request_id = ?
                  AND item.lifecycle_status IN ('PENDING', 'CLAIMED')
                  AND request.lease_token = ? AND request.lease_epoch = ?
                ORDER BY item.ordinal, item.id LIMIT 1 FOR UPDATE
                """, (result, number) -> new ClaimedItem(result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class), result.getObject(3, UUID.class),
                        result.getObject(4, UUID.class), result.getLong(5), result.getInt(6),
                        result.getObject(7, UUID.class), ReviewBulkActionCode.valueOf(result.getString(8)),
                        result.getObject(9, UUID.class), lease.leaseToken(), lease.epoch()),
                lease.merchantId(), lease.requestId(), lease.leaseToken(), lease.epoch());
        if (candidates.isEmpty()) return null;
        ClaimedItem item = candidates.getFirst();
        Instant now = clock.instant();
        int updated = jdbcTemplate.update("""
                UPDATE review_bulk_action_items
                SET lifecycle_status = 'CLAIMED', claim_lease_token = ?,
                    claim_lease_epoch = ?, claimed_at = ?
                WHERE merchant_id = ? AND id = ?
                  AND lifecycle_status IN ('PENDING', 'CLAIMED')
                """, lease.leaseToken(), lease.epoch(), Timestamp.from(now),
                lease.merchantId(), item.itemId());
        return updated == 1 ? item : null;
    }

    public void completeSuccessInCurrentTransaction(ClaimedItem item, long finalVersion) {
        Instant now = clock.instant();
        int updated = jdbcTemplate.update("""
                UPDATE review_bulk_action_items item
                SET lifecycle_status = 'SUCCEEDED', failure_code = NULL,
                    observed_assignment_version = ?, final_assignment_version = ?, completed_at = ?
                WHERE item.merchant_id = ? AND item.id = ? AND item.lifecycle_status = 'CLAIMED'
                  AND item.claim_lease_token = ? AND item.claim_lease_epoch = ?
                  AND EXISTS (SELECT 1 FROM review_bulk_action_requests request
                      WHERE request.merchant_id = item.merchant_id
                        AND request.id = item.bulk_action_request_id
                        AND request.lease_token = ? AND request.lease_epoch = ?)
                """, item.expectedAssignmentVersion(), finalVersion, Timestamp.from(now),
                item.merchantId(), item.itemId(), item.leaseToken(), item.leaseEpoch(),
                item.leaseToken(), item.leaseEpoch());
        if (updated != 1) throw new IllegalStateException("Bulk review item fence was lost");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeFailure(ClaimedItem item, String status, String failureCode,
            Long observedVersion) {
        Instant now = clock.instant();
        int updated = jdbcTemplate.update("""
                UPDATE review_bulk_action_items item
                SET lifecycle_status = ?, failure_code = ?, observed_assignment_version = ?,
                    completed_at = ?
                WHERE item.merchant_id = ? AND item.id = ? AND item.lifecycle_status = 'CLAIMED'
                  AND item.claim_lease_token = ? AND item.claim_lease_epoch = ?
                  AND EXISTS (SELECT 1 FROM review_bulk_action_requests request
                      WHERE request.merchant_id = item.merchant_id
                        AND request.id = item.bulk_action_request_id
                        AND request.lease_token = ? AND request.lease_epoch = ?)
                """, status, failureCode, observedVersion, Timestamp.from(now),
                item.merchantId(), item.itemId(), item.leaseToken(), item.leaseEpoch(),
                item.leaseToken(), item.leaseEpoch());
        if (updated != 1) throw new IllegalStateException("Bulk review item fence was lost");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRequest(Lease lease) {
        List<long[]> counts = jdbcTemplate.query("""
                SELECT COUNT(*) FILTER (WHERE lifecycle_status = 'SUCCEEDED'),
                       COUNT(*) FILTER (WHERE lifecycle_status IN
                         ('STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED')),
                       COUNT(*) FILTER (WHERE lifecycle_status IN ('PENDING', 'CLAIMED'))
                FROM review_bulk_action_items
                WHERE merchant_id = ? AND bulk_action_request_id = ?
                """, (result, number) -> new long[] {result.getLong(1), result.getLong(2),
                        result.getLong(3)}, lease.merchantId(), lease.requestId());
        long succeeded = counts.getFirst()[0];
        long failed = counts.getFirst()[1];
        long pending = counts.getFirst()[2];
        if (pending != 0) throw new IllegalStateException("Bulk review request still has pending items");
        String status = succeeded > 0 && failed > 0 ? "PARTIAL"
                : failed == 0 ? "COMPLETED" : "FAILED";
        Instant now = clock.instant();
        int updated = jdbcTemplate.update("""
                UPDATE review_bulk_action_requests
                SET lifecycle_status = ?, succeeded_count = ?, failed_count = ?,
                    completed_at = ?, updated_at = ?, lease_token = NULL,
                    lease_expires_at = NULL
                WHERE merchant_id = ? AND id = ? AND lease_token = ? AND lease_epoch = ?
                """, status, succeeded, failed, Timestamp.from(now), Timestamp.from(now),
                lease.merchantId(), lease.requestId(), lease.leaseToken(), lease.epoch());
        if (updated != 1) throw new IllegalStateException("Bulk review request fence was lost");
    }

    public List<UUID> recoverableRequestIds(int limit) {
        return jdbcTemplate.query("""
                SELECT id FROM review_bulk_action_requests
                WHERE lifecycle_status = 'PENDING'
                   OR (lifecycle_status = 'RUNNING' AND lease_expires_at <= ?)
                ORDER BY created_at, id LIMIT ?
                """, (result, number) -> result.getObject(1, UUID.class),
                Timestamp.from(clock.instant()), limit);
    }

    private RequestView requestView(java.sql.ResultSet result) throws java.sql.SQLException {
        return new RequestView(result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), ReviewBulkActionCode.valueOf(result.getString(4)),
                result.getObject(5, UUID.class), result.getString(6), result.getInt(7),
                result.getString(8), result.getInt(9), result.getInt(10), result.getLong(11),
                JdbcTime.instant(result, 12), JdbcTime.instant(result, 13),
                JdbcTime.instant(result, 14), JdbcTime.instant(result, 15));
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public record RequestView(UUID id, UUID merchantId, UUID initiatingOperatorId,
            ReviewBulkActionCode action, UUID targetOperatorId, String requestDigest,
            int itemCount, String lifecycleStatus, int succeededCount, int failedCount,
            long leaseEpoch, Instant createdAt, Instant startedAt, Instant completedAt,
            Instant updatedAt) {
    }

    public record ItemView(UUID id, UUID reviewCaseId, long expectedAssignmentVersion,
            int ordinal, String lifecycleStatus, String failureCode,
            Long observedAssignmentVersion, Long finalAssignmentVersion,
            Instant claimedAt, Instant completedAt) {
    }

    public record Lease(UUID requestId, UUID merchantId, UUID leaseToken, long epoch,
            String workerId) {
    }

    public record ClaimedItem(UUID itemId, UUID merchantId, UUID requestId,
            UUID reviewCaseId, long expectedAssignmentVersion, int ordinal,
            UUID initiatingOperatorId, ReviewBulkActionCode action, UUID targetOperatorId,
            UUID leaseToken, long leaseEpoch) {
    }

    private record ClaimCandidate(UUID id, UUID merchantId, long epoch, String status,
            Instant leaseExpiresAt) {
    }
}

