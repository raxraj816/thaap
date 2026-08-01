package com.thaap.payments.projection;

import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.platform.PlatformOperatorAuthenticator;
import com.thaap.payments.platform.PlatformOperatorContext;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuarantineRecoveryService {

    private static final String REQUEST_IDENTITY_DOMAIN =
            "platform.data.operation.request.identity";
    private static final String REQUEST_DIGEST_DOMAIN =
            "platform.data.operation.request.digest";
    private static final String OPERATION = "RETRY_QUARANTINE";

    private final PlatformOperatorAuthenticator authenticator;
    private final SensitiveValueHasher hasher;
    private final PaymentRiskProjectionConsumerService consumerService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public QuarantineRecoveryService(PlatformOperatorAuthenticator authenticator,
            SensitiveValueHasher hasher,
            PaymentRiskProjectionConsumerService consumerService,
            NamedParameterJdbcTemplate jdbcTemplate, AuditLogService auditLogService,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.authenticator = authenticator;
        this.hasher = hasher;
        this.consumerService = consumerService;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<QuarantineView> list(String rawCredential, String lifecycleStatus, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quarantine page size must be between 1 and 100");
        }
        String normalizedStatus = requireListStatus(lifecycleStatus);
        List<QuarantineView> result = transaction.execute(status -> {
            PlatformOperatorContext actor = authenticator
                    .authenticateContextForUpdate(rawCredential);
            requireDataOperator(actor);
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("consumer", ProjectionNames.PAYMENT_RISK_CONSUMER)
                    .addValue("status", normalizedStatus)
                    .addValue("limit", limit);
            return jdbcTemplate.query("""
                    SELECT inventory.id, inventory.reason_code,
                           inventory.lifecycle_status, inventory.event_type,
                           inventory.event_version, inventory.stream_type,
                           inventory.created_at, inventory.updated_at,
                           inventory.recoverable
                    FROM (
                        SELECT quarantine.id, quarantine.reason_code,
                               quarantine.lifecycle_status, event.event_type,
                               event.event_version, event.stream_type,
                               quarantine.created_at, quarantine.updated_at,
                               TRUE AS recoverable
                        FROM consumer_quarantine quarantine
                        JOIN domain_events event
                          ON event.merchant_id = quarantine.merchant_id
                         AND event.id = quarantine.domain_event_id
                        WHERE quarantine.consumer_name = :consumer
                          AND quarantine.lifecycle_status = :status
                        UNION ALL
                        SELECT transport.id, transport.reason_code,
                               transport.lifecycle_status,
                               CAST(NULL AS VARCHAR(80)) AS event_type,
                               CAST(NULL AS INTEGER) AS event_version,
                               CAST(NULL AS VARCHAR(32)) AS stream_type,
                               transport.created_at, transport.created_at AS updated_at,
                               transport.recoverable
                        FROM consumer_transport_quarantine transport
                        WHERE transport.consumer_name = :consumer
                          AND transport.lifecycle_status = :status
                    ) inventory
                    ORDER BY inventory.created_at, inventory.id
                    LIMIT :limit
                    """, parameters, (resultSet, rowNumber) -> new QuarantineView(
                            resultSet.getObject(1, UUID.class), resultSet.getString(2),
                            resultSet.getString(3), resultSet.getString(4),
                            resultSet.getObject(5, Integer.class), resultSet.getString(6),
                            JdbcTime.instant(resultSet, 7),
                            JdbcTime.instant(resultSet, 8),
                            resultSet.getBoolean(9)));
        });
        if (result == null) {
            throw new IllegalStateException("Quarantine inventory returned no result");
        }
        return result;
    }

    public RecoveryResult retry(String rawCredential, String idempotencyKey,
            UUID quarantineId, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        if (quarantineId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quarantine id is required");
        }
        RecoveryResult result = transaction.execute(status -> {
            PlatformOperatorContext actor = authenticator
                    .authenticateContextForUpdate(rawCredential);
            requireDataOperator(actor);
            List<SensitiveValueHasher.VersionedDigest> identities = requestIdentities(
                    actor, idempotencyKey);
            String canonicalRequest = "quarantineId=" + quarantineId;
            DataOperationRequest replay = findReplay(identities, actor,
                    quarantineId, canonicalRequest);
            if (replay != null) {
                return new RecoveryResult(replay.resultStatus(), 0, true);
            }
            if (isTransportQuarantine(quarantineId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Transport quarantine has no authenticated source and cannot be retried");
            }
            String lifecycle = quarantineLifecycle(quarantineId);
            if (lifecycle == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Quarantine item was not found");
            }
            if (!"OPEN".equals(lifecycle)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only an open quarantine item can be retried");
            }
            Instant now = clock.instant();
            int recoveredCount = consumerService.recoverFromImmutableSource(
                    quarantineId, actor.operatorId());
            persistRequest(identities.getFirst(), actor, quarantineId,
                    canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "consumer_quarantine.retry_completed", "consumer_quarantine",
                    quarantineId, "Data operator recovered immutable source event",
                    safeState("OPEN"), safeState("RESOLVED"));
            return new RecoveryResult("COMPLETED", recoveredCount, false);
        });
        if (result == null) {
            throw new IllegalStateException("Quarantine recovery returned no result");
        }
        return result;
    }

    private boolean isTransportQuarantine(UUID quarantineId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", quarantineId);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_transport_quarantine
                WHERE id = :id
                """, parameters, Integer.class);
        return count != null && count > 0;
    }

    private String quarantineLifecycle(UUID quarantineId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", quarantineId);
        List<String> matches = jdbcTemplate.query("""
                SELECT lifecycle_status FROM consumer_quarantine
                WHERE id = :id
                """, parameters, (resultSet, rowNumber) -> resultSet.getString(1));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private DataOperationRequest findReplay(
            List<SensitiveValueHasher.VersionedDigest> identities,
            PlatformOperatorContext actor, UUID quarantineId, String canonicalRequest) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("identities",
                identities.stream().map(SensitiveValueHasher.VersionedDigest::digest).toList());
        List<DataOperationRequest> matches = jdbcTemplate.query("""
                SELECT platform_operator_id, platform_credential_id, operation,
                       request_digest, request_digest_key_version, target_id,
                       result_status
                FROM platform_data_operation_requests
                WHERE request_identity_hmac IN (:identities)
                FOR UPDATE
                """, parameters, (resultSet, rowNumber) -> new DataOperationRequest(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getString(5),
                        resultSet.getObject(6, UUID.class), resultSet.getString(7)));
        if (matches.isEmpty()) {
            return null;
        }
        DataOperationRequest request = matches.getFirst();
        String expectedDigest = hasher.hmacSha256(REQUEST_DIGEST_DOMAIN,
                canonicalRequest, request.requestDigestKeyVersion());
        if (matches.size() != 1
                || !request.platformOperatorId().equals(actor.operatorId())
                || !request.platformCredentialId().equals(actor.credentialId())
                || !OPERATION.equals(request.operation())
                || !quarantineId.equals(request.targetId())
                || !constantTimeEquals(request.requestDigest(), expectedDigest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different data operation");
        }
        return request;
    }

    private void persistRequest(SensitiveValueHasher.VersionedDigest identity,
            PlatformOperatorContext actor, UUID quarantineId,
            String canonicalRequest, Instant now) {
        String digest = hasher.hmacSha256(REQUEST_DIGEST_DOMAIN,
                canonicalRequest, identity.keyVersion());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("operatorId", actor.operatorId())
                .addValue("credentialId", actor.credentialId())
                .addValue("operation", OPERATION)
                .addValue("identity", identity.digest())
                .addValue("identityVersion", identity.keyVersion())
                .addValue("digest", digest)
                .addValue("digestVersion", identity.keyVersion())
                .addValue("targetId", quarantineId)
                .addValue("resultId", quarantineId)
                .addValue("now", Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO platform_data_operation_requests (
                    id, platform_operator_id, platform_credential_id, operation,
                    request_identity_hmac, request_identity_key_version,
                    request_digest, request_digest_key_version, target_id,
                    result_id, result_status, created_at, completed_at)
                VALUES (:id, :operatorId, :credentialId, :operation,
                    :identity, :identityVersion, :digest, :digestVersion,
                    :targetId, :resultId, 'COMPLETED', :now, :now)
                """, parameters);
    }

    private List<SensitiveValueHasher.VersionedDigest> requestIdentities(
            PlatformOperatorContext actor, String idempotencyKey) {
        return hasher.hmacSha256Keyring(REQUEST_IDENTITY_DOMAIN,
                actor.operatorId() + "\n" + actor.credentialId() + "\n"
                        + OPERATION + "\n" + idempotencyKey);
    }

    private String requireListStatus(String value) {
        String normalized = value == null ? "OPEN" : value.trim().toUpperCase();
        if (!"OPEN".equals(normalized) && !"RETRY_REQUESTED".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quarantine status filter is invalid");
        }
        return normalized;
    }

    private void requireDataOperator(PlatformOperatorContext actor) {
        if (actor.role() != PlatformOperatorRole.DATA_OPERATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Data operator role is required");
        }
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 255 characters");
        }
    }

    private Map<String, Object> safeState(String lifecycleStatus) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("component", "payment_risk_projection");
        state.put("lifecycleStatus", lifecycleStatus);
        return state;
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    public record QuarantineView(UUID quarantineId, String reasonCode,
            String lifecycleStatus, String eventType, Integer eventVersion,
            String streamType, Instant createdAt, Instant updatedAt,
            boolean recoverable) {
    }

    public record RecoveryResult(String status, int recoveredCount, boolean replayed) {
    }

    private record DataOperationRequest(UUID platformOperatorId,
            UUID platformCredentialId, String operation, String requestDigest,
            String requestDigestKeyVersion, UUID targetId, String resultStatus) {
    }
}
