package com.thaap.payments.eventpublication;

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
public class PublicationRecoveryService {

    private static final String REQUEST_IDENTITY_DOMAIN =
            "platform.data.operation.request.identity";
    private static final String REQUEST_DIGEST_DOMAIN =
            "platform.data.operation.request.digest";
    private static final String OPERATION = "RETRY_PUBLICATION";

    private final PlatformOperatorAuthenticator authenticator;
    private final SensitiveValueHasher hasher;
    private final EventPublicationStore publicationStore;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PublicationRecoveryService(PlatformOperatorAuthenticator authenticator,
            SensitiveValueHasher hasher, EventPublicationStore publicationStore,
            NamedParameterJdbcTemplate namedJdbcTemplate,
            AuditLogService auditLogService, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.authenticator = authenticator;
        this.hasher = hasher;
        this.publicationStore = publicationStore;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public PublicationHealthSnapshot health(String rawCredential) {
        PublicationHealthSnapshot snapshot = transaction.execute(status -> {
            PlatformOperatorContext actor = authenticator
                    .authenticateContextForUpdate(rawCredential);
            requireDataOperator(actor);
            return publicationStore.healthSnapshot();
        });
        if (snapshot == null) {
            throw new IllegalStateException("Publication health returned no result");
        }
        return snapshot;
    }

    public RecoveryResult retry(String rawCredential, String idempotencyKey,
            UUID publicationId, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        if (publicationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Publication id is required");
        }
        RecoveryResult result = transaction.execute(status -> {
            PlatformOperatorContext actor = authenticator
                    .authenticateContextForUpdate(rawCredential);
            requireDataOperator(actor);
            List<SensitiveValueHasher.VersionedDigest> identities = requestIdentities(
                    actor, idempotencyKey);
            String canonicalRequest = "publicationId=" + publicationId;
            DataOperationRequest replay = findReplay(identities, actor,
                    publicationId, canonicalRequest);
            if (replay != null) {
                return new RecoveryResult(replay.resultStatus(), true);
            }

            String lifecycleStatus = publicationStore
                    .lifecycleStatusForUpdate(publicationId);
            if (lifecycleStatus == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Publication was not found");
            }
            if (!"BLOCKED".equals(lifecycleStatus)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a blocked publication can be retried");
            }
            Instant now = clock.instant();
            if (!publicationStore.reopenBlocked(publicationId, now)) {
                throw new IllegalStateException(
                        "Locked blocked publication could not be reopened");
            }
            persistRequest(identities.getFirst(), actor, publicationId,
                    canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "event_publication.retry_requested", "event_publication",
                    publicationId, "Data operator reopened blocked publication",
                    safeState("BLOCKED"), safeState("PENDING"));
            return new RecoveryResult("COMPLETED", false);
        });
        if (result == null) {
            throw new IllegalStateException("Publication recovery returned no result");
        }
        return result;
    }

    private DataOperationRequest findReplay(
            List<SensitiveValueHasher.VersionedDigest> identities,
            PlatformOperatorContext actor, UUID publicationId,
            String canonicalRequest) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("identities",
                identities.stream().map(SensitiveValueHasher.VersionedDigest::digest).toList());
        List<DataOperationRequest> matches = namedJdbcTemplate.query("""
                SELECT platform_operator_id, platform_credential_id, operation,
                       request_digest, request_digest_key_version, target_id,
                       result_status
                  FROM platform_data_operation_requests
                 WHERE request_identity_hmac IN (:identities)
                 FOR UPDATE
                """, parameters, (resultSet, rowNumber) -> new DataOperationRequest(
                        resultSet.getObject("platform_operator_id", UUID.class),
                        resultSet.getObject("platform_credential_id", UUID.class),
                        resultSet.getString("operation"),
                        resultSet.getString("request_digest"),
                        resultSet.getString("request_digest_key_version"),
                        resultSet.getObject("target_id", UUID.class),
                        resultSet.getString("result_status")));
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Platform data request identities are ambiguous");
        }
        DataOperationRequest request = matches.getFirst();
        String expectedDigest = hasher.hmacSha256(REQUEST_DIGEST_DOMAIN,
                canonicalRequest, request.requestDigestKeyVersion());
        if (!request.platformOperatorId().equals(actor.operatorId())
                || !request.platformCredentialId().equals(actor.credentialId())
                || !OPERATION.equals(request.operation())
                || !publicationId.equals(request.targetId())
                || !constantTimeEquals(request.requestDigest(), expectedDigest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different data operation");
        }
        return request;
    }

    private void persistRequest(SensitiveValueHasher.VersionedDigest identity,
            PlatformOperatorContext actor, UUID publicationId,
            String canonicalRequest, Instant now) {
        String digest = hasher.hmacSha256(REQUEST_DIGEST_DOMAIN,
                canonicalRequest, identity.keyVersion());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("operatorId", actor.operatorId())
                .addValue("credentialId", actor.credentialId())
                .addValue("operation", OPERATION)
                .addValue("identity", identity.digest())
                .addValue("identityKeyVersion", identity.keyVersion())
                .addValue("digest", digest)
                .addValue("digestKeyVersion", identity.keyVersion())
                .addValue("targetId", publicationId)
                .addValue("resultId", publicationId)
                .addValue("resultStatus", "COMPLETED")
                .addValue("now", Timestamp.from(now));
        namedJdbcTemplate.update("""
                INSERT INTO platform_data_operation_requests (
                    id, platform_operator_id, platform_credential_id, operation,
                    request_identity_hmac, request_identity_key_version,
                    request_digest, request_digest_key_version, target_id,
                    result_id, result_status, created_at, completed_at)
                VALUES (:id, :operatorId, :credentialId, :operation,
                    :identity, :identityKeyVersion, :digest, :digestKeyVersion,
                    :targetId, :resultId, :resultStatus, :now, :now)
                """, parameters);
    }

    private List<SensitiveValueHasher.VersionedDigest> requestIdentities(
            PlatformOperatorContext actor, String idempotencyKey) {
        return hasher.hmacSha256Keyring(REQUEST_IDENTITY_DOMAIN,
                actor.operatorId() + "\n" + actor.credentialId() + "\n"
                        + OPERATION + "\n" + idempotencyKey);
    }

    private void requireDataOperator(PlatformOperatorContext actor) {
        if (actor.role() != PlatformOperatorRole.DATA_OPERATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Data operator role is required");
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing Idempotency-Key header");
        }
        if (key.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain at most 255 characters");
        }
    }

    private Map<String, Object> safeState(String lifecycleStatus) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("component", "event_publisher");
        state.put("lifecycleStatus", lifecycleStatus);
        return state;
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    public record RecoveryResult(String status, boolean replayed) {
    }

    private record DataOperationRequest(UUID platformOperatorId,
            UUID platformCredentialId, String operation, String requestDigest,
            String requestDigestKeyVersion, UUID targetId,
            String resultStatus) {
    }
}
