package com.thaap.payments.risk;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookEncryptedSecret;
import com.thaap.payments.webhook.WebhookSecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskOperatorService {

    private static final Duration CREATION_REPLAY_TTL = Duration.ofHours(24);

    private final RiskOperatorRepository operatorRepository;
    private final RiskOperatorCredentialRepository credentialRepository;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final WebhookSecretCipher secretCipher;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RiskOperatorService(RiskOperatorRepository operatorRepository,
            RiskOperatorCredentialRepository credentialRepository,
            SensitiveValueHasher sensitiveValueHasher, WebhookSecretCipher secretCipher,
            AuditLogService auditLogService, Clock clock, PlatformTransactionManager transactionManager) {
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.secretCipher = secretCipher;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public CreationResult create(MerchantContext merchantContext, String idempotencyKey,
            RiskOperatorRole role, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        List<SensitiveValueHasher.VersionedDigest> identities = sensitiveValueHasher.hmacSha256Keyring(
                "risk.operator.creation.idempotency", idempotencyKey);
        CreationResult replay = transaction.execute(status -> findReplay(merchantContext, identities, role));
        if (replay != null) {
            return replay;
        }
        try {
            CreationResult created = transaction.execute(status -> createOnce(
                    merchantContext, identities.getFirst(), role, requestId));
            if (created == null) {
                throw new IllegalStateException("Risk operator creation returned no result");
            }
            return created;
        } catch (DataIntegrityViolationException exception) {
            CreationResult concurrentReplay = transaction.execute(status ->
                    findReplay(merchantContext, identities, role));
            if (concurrentReplay != null) {
                return concurrentReplay;
            }
            throw exception;
        }
    }

    public RiskOperator disable(MerchantContext merchantContext, UUID operatorId, String requestId) {
        RiskOperator operator = operatorRepository.findByIdAndMerchantIdForUpdate(
                        operatorId, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Risk operator was not found"));
        if (!operator.isActive()) {
            return operator;
        }
        Instant now = clock.instant();
        operator.disable(now);
        credentialRepository.findByMerchantIdAndRiskOperatorId(merchantContext.merchantId(), operatorId)
                .ifPresent(credential -> credential.disable(now));
        auditLogService.recordMerchantApiAction(merchantContext, requestId,
                "risk_operator.disabled", "risk_operator", operatorId,
                "Merchant disabled risk operator", null, safeOperatorState(operator));
        return operator;
    }

    private CreationResult createOnce(MerchantContext merchantContext,
            SensitiveValueHasher.VersionedDigest creationIdentity, RiskOperatorRole role, String requestId) {
        Instant now = clock.instant();
        RiskOperator operator = operatorRepository.save(new RiskOperator(merchantContext.merchantId(), role,
                merchantContext.apiKeyPrefix(), now));
        String rawSecret = "thaap_risk_" + UUID.randomUUID().toString().replace("-", "");
        SensitiveValueHasher.VersionedDigest secretIdentity = sensitiveValueHasher.hmacSha256Keyring(
                "risk.operator.credential", rawSecret).getFirst();
        WebhookEncryptedSecret encryptedSecret = secretCipher.encrypt(
                rawSecret, merchantContext.merchantId(), operator.getId());
        String requestHash = requestHash(role, creationIdentity.keyVersion());
        RiskOperatorCredential credential = credentialRepository.saveAndFlush(new RiskOperatorCredential(
                merchantContext.merchantId(), operator.getId(), SecretHasher.prefix(rawSecret),
                SecretHasher.sha256(rawSecret), secretIdentity.digest(), secretIdentity.keyVersion(),
                creationIdentity.digest(), requestHash, creationIdentity.keyVersion(), encryptedSecret,
                now, now.plus(CREATION_REPLAY_TTL)));
        auditLogService.recordMerchantApiAction(merchantContext, requestId,
                "risk_operator.created", "risk_operator", operator.getId(),
                "Merchant created risk operator", null, safeOperatorState(operator));
        return new CreationResult(operator, credential.getSecretPrefix(), rawSecret);
    }

    private CreationResult findReplay(MerchantContext merchantContext,
            List<SensitiveValueHasher.VersionedDigest> identities, RiskOperatorRole role) {
        List<RiskOperatorCredential> matches = credentialRepository
                .findByMerchantIdAndCreationIdentityHmacIn(merchantContext.merchantId(),
                        identities.stream().map(SensitiveValueHasher.VersionedDigest::digest).toList());
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Risk operator creation identities are ambiguous");
        }
        RiskOperatorCredential credential = matches.getFirst();
        String expectedHash = requestHash(role, credential.getCreationHashKeyVersion());
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                credential.getCreationRequestHash().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different risk operator request");
        }
        if (!credential.getReplayExpiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Risk operator creation replay window has expired");
        }
        RiskOperator operator = operatorRepository.findByIdAndMerchantId(
                        credential.getRiskOperatorId(), merchantContext.merchantId())
                .orElseThrow(() -> new IllegalStateException(
                        "Risk operator credential lost operator lineage"));
        String rawSecret = secretCipher.decrypt(credential.encryptedReplay(),
                merchantContext.merchantId(), operator.getId());
        return new CreationResult(operator, credential.getSecretPrefix(), rawSecret);
    }

    private String requestHash(RiskOperatorRole role, String keyVersion) {
        return sensitiveValueHasher.hmacSha256("risk.operator.creation.request", role.name(), keyVersion);
    }

    private Map<String, Object> safeOperatorState(RiskOperator operator) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("operatorCode", operator.getOperatorCode());
        state.put("role", operator.getRole().name());
        state.put("status", operator.getStatus().name());
        state.put("lifecycleEpoch", operator.getLifecycleEpoch());
        return state;
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header");
        }
        if (key.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain at most 255 characters");
        }
    }

    public record CreationResult(RiskOperator operator, String credentialPrefix, String rawSecret) {
    }
}
