package com.thaap.payments.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookEndpointVerificationService {

    private static final String IDENTITY_DOMAIN = "webhook.verification.idempotency";

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookEndpointSecretService secretService;
    private final WebhookSigner signer;
    private final WebhookHttpTransport transport;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final AuditLogService auditLogService;
    private final WebhookOperationsMetrics metrics;
    private final Clock clock;
    private final Duration challengeTtl;
    private final Duration dispatchLease;
    private final RiskOperationsEventProducer eventProducer;
    private final TransactionTemplate transaction;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebhookEndpointVerificationService(WebhookEndpointRepository endpointRepository,
            WebhookTargetPolicy targetPolicy, WebhookEndpointSecretService secretService,
            WebhookSigner signer, WebhookHttpTransport transport, JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper, SensitiveValueHasher sensitiveValueHasher,
            AuditLogService auditLogService, WebhookOperationsMetrics metrics, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${thaap.webhook.verification-challenge-ttl:2m}") Duration challengeTtl,
            @Value("${thaap.webhook.lease-duration:30s}") Duration dispatchLease,
            RiskOperationsEventProducer eventProducer) {
        if (challengeTtl.compareTo(Duration.ofSeconds(30)) < 0
                || challengeTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "Webhook verification challenge TTL must be between 30 seconds and 5 minutes");
        }
        if (dispatchLease == null || dispatchLease.isZero() || dispatchLease.isNegative()) {
            throw new IllegalArgumentException(
                    "Webhook verification dispatch lease must be positive");
        }
        this.endpointRepository = endpointRepository;
        this.targetPolicy = targetPolicy;
        this.secretService = secretService;
        this.signer = signer;
        this.transport = transport;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.clock = clock;
        this.challengeTtl = challengeTtl;
        this.dispatchLease = dispatchLease;
        this.eventProducer = eventProducer;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ChallengeView verify(MerchantContext context, UUID endpointId,
            String idempotencyKey, String requestId) {
        PreparedChallenge prepared = transaction.execute(status ->
                prepare(context, endpointId, idempotencyKey, requestId));
        if (prepared == null) {
            throw new IllegalStateException("Webhook verification preparation returned no result");
        }
        if (prepared.recoveredView() != null) {
            return prepared.recoveredView();
        }
        ValidatedWebhookTarget target;
        try {
            target = targetPolicy.validate(prepared.normalizedUrl());
        } catch (RuntimeException exception) {
            return finish(context, prepared, null, null,
                    WebhookVerificationFailureCode.TARGET_REJECTED, requestId);
        }
        WebhookSignature signature;
        try {
            signature = secretService.signForDelivery(context.merchantId(), endpointId,
                    prepared.signingGeneration(), prepared.payload(), clock.instant(), signer);
        } catch (RuntimeException exception) {
            return finish(context, prepared, null, null,
                    WebhookVerificationFailureCode.ENDPOINT_CHANGED, requestId);
        }
        DispatchPermit permit = acquireDispatchPermit(context.merchantId(), prepared);
        if (permit.terminalView() != null) {
            return permit.terminalView();
        }
        if (permit.failure() != null) {
            return finish(context, prepared, null, null, permit.failure(), requestId);
        }
        WebhookHttpResponse response;
        try {
            response = transport.send(new WebhookHttpRequest(target, prepared.id(),
                    prepared.id(), signature.headerValue(), prepared.payload()));
        } catch (WebhookTransportException exception) {
            return finish(context, prepared, null, null,
                    WebhookVerificationFailureCode.TRANSPORT_FAILED, requestId);
        }
        if (!response.isSuccessful()) {
            return finish(context, prepared, response.statusCode(), null,
                    WebhookVerificationFailureCode.NON_SUCCESS_STATUS, requestId);
        }
        if (response.verificationToken() == null) {
            return finish(context, prepared, response.statusCode(), null,
                    WebhookVerificationFailureCode.MISSING_RESPONSE_FIELD, requestId);
        }
        boolean matches = MessageDigest.isEqual(
                SecretHasher.sha256(response.verificationToken()).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                prepared.nonceSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return finish(context, prepared, response.statusCode(), matches,
                matches ? null : WebhookVerificationFailureCode.TOKEN_MISMATCH, requestId);
    }

    public ChallengeView get(MerchantContext context, UUID endpointId, UUID challengeId) {
        return transaction.execute(status -> {
            requireEndpoint(context.merchantId(), endpointId, false);
            return requireChallenge(context.merchantId(), endpointId, challengeId, false);
        });
    }

    public int expireDue(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Webhook verification expiry batch size must be between 1 and 100");
        }
        Instant now = clock.instant();
        Instant abandonedDispatchCutoff = now.minus(dispatchLease);
        List<DueChallenge> due = jdbcTemplate.query("""
                SELECT merchant_id, webhook_endpoint_id, id
                  FROM webhook_endpoint_verifications
                 WHERE lifecycle_status = 'PENDING' AND expires_at <= ?
                   AND (dispatch_started_at IS NULL OR dispatch_started_at <= ?)
                 ORDER BY expires_at, id
                 LIMIT ?
                """, (resultSet, rowNumber) -> new DueChallenge(
                        resultSet.getObject("merchant_id", UUID.class),
                        resultSet.getObject("webhook_endpoint_id", UUID.class),
                        resultSet.getObject("id", UUID.class)), Timestamp.from(now),
                Timestamp.from(abandonedDispatchCutoff), limit);
        int expired = 0;
        for (DueChallenge challenge : due) {
            Boolean changed = transaction.execute(status -> expireLocked(challenge, now));
            if (Boolean.TRUE.equals(changed)) {
                expired++;
            }
        }
        return expired;
    }

    private boolean expireLocked(DueChallenge due, Instant now) {
        WebhookEndpoint endpoint = requireEndpoint(due.merchantId(), due.endpointId(), true);
        ChallengeView challenge = requireChallenge(
                due.merchantId(), due.endpointId(), due.challengeId(), true);
        if (challenge.lifecycleStatus() != WebhookEndpointVerificationStatus.PENDING
                || challenge.expiresAt().isAfter(now)
                || dispatchLeaseIsActive(due.merchantId(), due.challengeId(), now)) {
            return false;
        }
        jdbcTemplate.update("""
                UPDATE webhook_endpoint_verifications
                   SET lifecycle_status = 'EXPIRED', failure_code = 'EXPIRED', completed_at = ?
                 WHERE merchant_id = ? AND id = ?
                """, Timestamp.from(now), due.merchantId(), due.challengeId());
        insertEvent(due.merchantId(), due.challengeId(),
                nextSequence(due.merchantId(), due.challengeId()), "EXPIRED", "EXPIRED", now);
        if (due.challengeId().equals(endpoint.getPendingVerificationId())) {
            endpoint.finishVerification(WebhookEndpointVerificationStatus.EXPIRED);
        }
        metrics.record("VERIFICATION_EXPIRY", "EXPIRED", "SYSTEM");
        return true;
    }

    private PreparedChallenge prepare(MerchantContext context, UUID endpointId,
            String idempotencyKey, String requestId) {
        WebhookEndpoint endpoint = requireEndpoint(context.merchantId(), endpointId, true);
        List<SensitiveValueHasher.VersionedDigest> identities = sensitiveValueHasher.hmacSha256Keyring(
                IDENTITY_DOMAIN, idempotencyKey);
        ChallengeView existing = findByIdentity(context.merchantId(), endpointId, identities);
        if (existing != null) {
            return PreparedChallenge.recovered(existing);
        }
        expirePreviousPending(context.merchantId(), endpoint, clock.instant());
        UUID challengeId = UUID.randomUUID();
        String token = randomToken();
        String tokenHash = SecretHasher.sha256(token);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(challengeTtl);
        SensitiveValueHasher.VersionedDigest identity = identities.getFirst();
        String payload = canonical(Map.of(
                "challenge", token,
                "challengeId", challengeId.toString(),
                "endpointId", endpointId.toString(),
                "expiresAt", expiresAt.toString(),
                "signingSecretGeneration", endpoint.getSigningSecretGeneration().toString(),
                "type", "webhook.endpoint.verification"));
        jdbcTemplate.update("""
                INSERT INTO webhook_endpoint_verifications
                    (id, merchant_id, webhook_endpoint_id, normalized_url,
                     signing_secret_generation, nonce_sha256, request_identity_hmac,
                     request_identity_key_version, lifecycle_status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, challengeId, context.merchantId(), endpointId, endpoint.getUrl(),
                endpoint.getSigningSecretGeneration(), tokenHash, identity.digest(),
                identity.keyVersion(), Timestamp.from(expiresAt), Timestamp.from(createdAt));
        insertEvent(context.merchantId(), challengeId, 1, "CHALLENGE_CREATED", "PENDING", createdAt);
        endpoint.beginVerification(challengeId);
        auditLogService.recordMerchantApiAction(context, requestId, "webhook.endpoint.verification_requested",
                "webhook_endpoint", endpointId, "Signed endpoint verification requested",
                null, Map.of("verificationStatus", "PENDING"));
        metrics.record("VERIFICATION", "PENDING", "MERCHANT_API");
        return new PreparedChallenge(challengeId, endpointId, endpoint.getUrl(),
                endpoint.getSigningSecretGeneration(), tokenHash, payload,
                expiresAt, null);
    }

    DispatchPermit acquireDispatchPermit(UUID merchantId, PreparedChallenge prepared) {
        DispatchPermit result = transaction.execute(status -> {
            WebhookEndpoint endpoint = requireEndpoint(merchantId, prepared.endpointId(), true);
            ChallengeView challenge = requireChallenge(
                    merchantId, prepared.endpointId(), prepared.id(), true);
            if (challenge.lifecycleStatus() != WebhookEndpointVerificationStatus.PENDING) {
                return DispatchPermit.terminal(challenge);
            }
            if (dispatchStartedAt(merchantId, prepared.id()) != null) {
                return DispatchPermit.terminal(challenge);
            }
            Instant dispatchAt = clock.instant();
            if (!dispatchAt.isBefore(prepared.expiresAt())) {
                return DispatchPermit.failed(WebhookVerificationFailureCode.EXPIRED);
            }
            if (!endpoint.isActive()) {
                return DispatchPermit.failed(WebhookVerificationFailureCode.ENDPOINT_DISABLED);
            }
            if (!prepared.signingGeneration().equals(endpoint.getSigningSecretGeneration())
                    || !prepared.normalizedUrl().equals(endpoint.getUrl())
                    || !prepared.id().equals(endpoint.getPendingVerificationId())) {
                return DispatchPermit.failed(WebhookVerificationFailureCode.ENDPOINT_CHANGED);
            }
            jdbcTemplate.update("""
                    UPDATE webhook_endpoint_verifications SET dispatch_started_at = ?
                     WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(dispatchAt), merchantId, prepared.id());
            insertEvent(merchantId, prepared.id(), 2,
                    "DISPATCH_STARTED", "DISPATCHED", dispatchAt);
            return DispatchPermit.granted(dispatchAt);
        });
        if (result == null) {
            throw new IllegalStateException(
                    "Webhook verification dispatch authorization returned no result");
        }
        return result;
    }

    private ChallengeView finish(MerchantContext context, PreparedChallenge prepared,
            Integer responseStatus, Boolean tokenMatches,
            WebhookVerificationFailureCode failure, String requestId) {
        ChallengeView result = transaction.execute(status -> {
            WebhookEndpoint endpoint = requireEndpoint(context.merchantId(), prepared.endpointId(), true);
            ChallengeView current = requireChallenge(context.merchantId(), prepared.endpointId(),
                    prepared.id(), true);
            if (current.lifecycleStatus() != WebhookEndpointVerificationStatus.PENDING) {
                return current;
            }
            Instant now = clock.instant();
            WebhookVerificationFailureCode resolvedFailure = failure;
            if (!now.isBefore(prepared.expiresAt())) {
                resolvedFailure = WebhookVerificationFailureCode.EXPIRED;
            } else if (!endpoint.isActive()) {
                resolvedFailure = WebhookVerificationFailureCode.ENDPOINT_DISABLED;
            } else if (!prepared.signingGeneration().equals(endpoint.getSigningSecretGeneration())
                    || !prepared.normalizedUrl().equals(endpoint.getUrl())
                    || !prepared.id().equals(endpoint.getPendingVerificationId())) {
                resolvedFailure = WebhookVerificationFailureCode.ENDPOINT_CHANGED;
            }
            boolean verified = resolvedFailure == null && Boolean.TRUE.equals(tokenMatches);
            WebhookEndpointVerificationStatus lifecycle = verified
                    ? WebhookEndpointVerificationStatus.VERIFIED
                    : resolvedFailure == WebhookVerificationFailureCode.EXPIRED
                            ? WebhookEndpointVerificationStatus.EXPIRED
                            : WebhookEndpointVerificationStatus.FAILED;
            if (verified) {
                endpoint.verify(prepared.normalizedUrl(), prepared.signingGeneration(), now);
            } else if (prepared.id().equals(endpoint.getPendingVerificationId())) {
                endpoint.finishVerification(lifecycle);
            }
            jdbcTemplate.update("""
                    UPDATE webhook_endpoint_verifications
                       SET lifecycle_status = ?, response_status = ?, failure_code = ?, completed_at = ?
                     WHERE merchant_id = ? AND id = ?
                    """, lifecycle.name(), responseStatus,
                    resolvedFailure == null ? null : resolvedFailure.name(),
                    Timestamp.from(now), context.merchantId(), prepared.id());
            insertEvent(context.merchantId(), prepared.id(),
                    nextSequence(context.merchantId(), prepared.id()),
                    lifecycle.name(), verified ? "VERIFIED" : resolvedFailure.name(), now);
            if (verified) {
                eventProducer.webhookEndpointVerified(context, prepared.endpointId(), now,
                        requestId, prepared.id());
            }
            auditLogService.recordMerchantApiAction(context, requestId,
                    "webhook.endpoint.verification_completed", "webhook_endpoint",
                    prepared.endpointId(), "Endpoint verification reached a terminal state",
                    Map.of("verificationStatus", "PENDING"),
                    Map.of("verificationStatus", lifecycle.name()));
            metrics.record("VERIFICATION", lifecycle.name(), "MERCHANT_API");
            return requireChallenge(context.merchantId(), prepared.endpointId(), prepared.id(), false);
        });
        if (result == null) {
            throw new IllegalStateException("Webhook verification finalization returned no result");
        }
        return result;
    }

    private void expirePreviousPending(UUID merchantId, WebhookEndpoint endpoint, Instant now) {
        UUID pendingId = endpoint.getPendingVerificationId();
        if (pendingId == null) {
            return;
        }
        ChallengeView previous = requireChallenge(merchantId, endpoint.getId(), pendingId, true);
        if (previous.lifecycleStatus() == WebhookEndpointVerificationStatus.PENDING) {
            if (previous.expiresAt().isAfter(now)
                    || dispatchLeaseIsActive(merchantId, pendingId, now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Webhook endpoint verification is already in progress");
            }
            jdbcTemplate.update("""
                    UPDATE webhook_endpoint_verifications
                       SET lifecycle_status = 'EXPIRED', failure_code = 'EXPIRED', completed_at = ?
                     WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(now), merchantId, pendingId);
            insertEvent(merchantId, pendingId, nextSequence(merchantId, pendingId),
                    "EXPIRED", "EXPIRED", now);
            endpoint.finishVerification(WebhookEndpointVerificationStatus.EXPIRED);
        }
    }

    private boolean dispatchLeaseIsActive(UUID merchantId, UUID challengeId, Instant now) {
        Timestamp dispatchStartedAt = dispatchStartedAt(merchantId, challengeId);
        return dispatchStartedAt != null
                && dispatchStartedAt.toInstant().plus(dispatchLease).isAfter(now);
    }

    private Timestamp dispatchStartedAt(UUID merchantId, UUID challengeId) {
        return jdbcTemplate.queryForObject("""
                SELECT dispatch_started_at
                  FROM webhook_endpoint_verifications
                 WHERE merchant_id = ? AND id = ?
                """, Timestamp.class, merchantId, challengeId);
    }

    private ChallengeView findByIdentity(UUID merchantId, UUID endpointId,
            List<SensitiveValueHasher.VersionedDigest> identities) {
        for (SensitiveValueHasher.VersionedDigest identity : identities) {
            List<UUID> ids = jdbcTemplate.queryForList("""
                    SELECT id FROM webhook_endpoint_verifications
                     WHERE merchant_id = ? AND webhook_endpoint_id = ?
                       AND request_identity_hmac = ?
                    """, UUID.class, merchantId, endpointId, identity.digest());
            if (!ids.isEmpty()) {
                return requireChallenge(merchantId, endpointId, ids.getFirst(), true);
            }
        }
        return null;
    }

    private WebhookEndpoint requireEndpoint(UUID merchantId, UUID endpointId, boolean lock) {
        return (lock ? endpointRepository.findByIdAndMerchantIdForUpdate(endpointId, merchantId)
                : endpointRepository.findByIdAndMerchantId(endpointId, merchantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Webhook endpoint was not found"));
    }

    private ChallengeView requireChallenge(UUID merchantId, UUID endpointId,
            UUID challengeId, boolean lock) {
        String endpointClause = endpointId == null ? "" : " AND webhook_endpoint_id = ?";
        Object[] arguments = endpointId == null
                ? new Object[] {merchantId, challengeId}
                : new Object[] {merchantId, challengeId, endpointId};
        String lockClause = lock ? " FOR UPDATE" : "";
        List<ChallengeView> rows = jdbcTemplate.query("""
                SELECT id, webhook_endpoint_id, lifecycle_status, expires_at,
                       response_status, failure_code, created_at, completed_at
                  FROM webhook_endpoint_verifications
                 WHERE merchant_id = ? AND id = ?
                """ + endpointClause + lockClause,
                (resultSet, rowNumber) -> new ChallengeView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("webhook_endpoint_id", UUID.class),
                        WebhookEndpointVerificationStatus.valueOf(
                                resultSet.getString("lifecycle_status")),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        (Integer) resultSet.getObject("response_status"),
                        resultSet.getString("failure_code"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("completed_at") == null ? null
                                : resultSet.getTimestamp("completed_at").toInstant()), arguments);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Webhook endpoint verification was not found");
        }
        return rows.getFirst();
    }

    private void insertEvent(UUID merchantId, UUID verificationId, int sequence,
            String type, String outcome, Instant occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO webhook_endpoint_verification_events
                    (id, merchant_id, webhook_endpoint_verification_id,
                     event_sequence, event_type, outcome_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), merchantId, verificationId, sequence,
                type, outcome, Timestamp.from(occurredAt));
    }

    private int nextSequence(UUID merchantId, UUID verificationId) {
        Integer sequence = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(event_sequence), 0) + 1
                  FROM webhook_endpoint_verification_events
                 WHERE merchant_id = ? AND webhook_endpoint_verification_id = ?
                """, Integer.class, merchantId, verificationId);
        return sequence == null ? 1 : sequence;
    }

    private String canonical(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize webhook verification challenge", exception);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ChallengeView(UUID id, UUID endpointId,
            WebhookEndpointVerificationStatus lifecycleStatus, Instant expiresAt,
            Integer responseStatus, String failureCode, Instant createdAt,
            Instant completedAt) { }

    record PreparedChallenge(UUID id, UUID endpointId, String normalizedUrl,
            UUID signingGeneration, String nonceSha256,
            String payload, Instant expiresAt, ChallengeView recoveredView) {

        static PreparedChallenge recovered(ChallengeView view) {
            return new PreparedChallenge(view.id(), view.endpointId(), null, null,
                    null, null, view.expiresAt(), view);
        }
    }

    record DispatchPermit(Instant dispatchAt, ChallengeView terminalView,
            WebhookVerificationFailureCode failure) {

        static DispatchPermit granted(Instant dispatchAt) {
            return new DispatchPermit(dispatchAt, null, null);
        }

        static DispatchPermit terminal(ChallengeView terminalView) {
            return new DispatchPermit(null, terminalView, null);
        }

        static DispatchPermit failed(WebhookVerificationFailureCode failure) {
            return new DispatchPermit(null, null, failure);
        }
    }

    private record DueChallenge(UUID merchantId, UUID endpointId, UUID challengeId) { }
}
