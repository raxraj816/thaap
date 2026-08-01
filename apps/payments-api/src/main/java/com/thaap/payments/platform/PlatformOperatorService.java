package com.thaap.payments.platform;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformOperatorService {

    private static final String CREDENTIAL_DOMAIN = "platform.operator.credential";
    private static final String REQUEST_IDENTITY_DOMAIN = "platform.operator.request.identity";
    private static final String REQUEST_DIGEST_DOMAIN = "platform.operator.request.digest";

    private final PlatformOperatorRepository operatorRepository;
    private final PlatformOperatorCredentialRepository credentialRepository;
    private final PlatformOperatorBootstrapStateRepository bootstrapStateRepository;
    private final PlatformOperatorRequestRepository requestRepository;
    private final PlatformOperatorAuthenticator authenticator;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final TransactionTemplate transaction;
    private final String bootstrapToken;

    public PlatformOperatorService(PlatformOperatorRepository operatorRepository,
            PlatformOperatorCredentialRepository credentialRepository,
            PlatformOperatorBootstrapStateRepository bootstrapStateRepository,
            PlatformOperatorRequestRepository requestRepository,
            PlatformOperatorAuthenticator authenticator,
            SensitiveValueHasher sensitiveValueHasher, AuditLogService auditLogService,
            Clock clock, PlatformTransactionManager transactionManager,
            @Value("${thaap.platform.bootstrap-token:}") String bootstrapToken) {
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.bootstrapStateRepository = bootstrapStateRepository;
        this.requestRepository = requestRepository;
        this.authenticator = authenticator;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.secureRandom = new SecureRandom();
        this.transaction = new TransactionTemplate(transactionManager);
        this.bootstrapToken = bootstrapToken;
    }

    public MutationResult bootstrap(String providedBootstrapToken, String idempotencyKey,
            String displayName, String requestId) {
        requireBootstrapToken(providedBootstrapToken);
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = normalizeDisplayName(displayName);
        List<SensitiveValueHasher.VersionedDigest> identities = requestIdentities(
                "BOOTSTRAP\n" + providedBootstrapToken, PlatformOperatorOperation.BOOTSTRAP,
                idempotencyKey);
        MutationResult result = transaction.execute(status -> {
            PlatformOperatorBootstrapState state = bootstrapStateRepository.findForUpdate()
                    .orElseThrow(() -> new IllegalStateException(
                            "Platform bootstrap singleton is missing"));
            MutationResult replay = findReplay(identities,
                    PlatformOperatorPrincipalKind.BOOTSTRAP, null, null,
                    PlatformOperatorOperation.BOOTSTRAP,
                    bootstrapDigest(normalizedName));
            if (replay != null) {
                return replay;
            }
            if (state.isBootstrapCompleted()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Platform operator bootstrap is already complete");
            }
            Instant now = clock.instant();
            PlatformOperator operator = operatorRepository.saveAndFlush(
                    new PlatformOperator(normalizedName,
                            PlatformOperatorRole.PLATFORM_ADMIN, null, now));
            IssuedCredential issued = issueCredential(operator.getId(), now);
            state.complete(operator.getId(), now);
            persistRequest(PlatformOperatorPrincipalKind.BOOTSTRAP, null, null,
                    null, PlatformOperatorOperation.BOOTSTRAP, identities.getFirst(),
                    bootstrapDigest(normalizedName), operator.getId(), now);
            auditLogService.recordPlatformBootstrapAction(requestId,
                    "platform_operator.bootstrapped", "platform_operator", operator.getId(),
                    "First platform administrator bootstrapped", null,
                    safeOperatorState(operator));
            return MutationResult.created(operator, issued.credential().getSecretPrefix(),
                    issued.rawSecret());
        });
        if (result == null) {
            throw new IllegalStateException("Platform bootstrap returned no result");
        }
        return result;
    }

    public PlatformOperatorContext session(String rawCredential) {
        return authenticator.authenticate(rawCredential).context();
    }

    public MutationResult create(String rawCredential, String idempotencyKey,
            String displayName, PlatformOperatorRole role, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = normalizeDisplayName(displayName);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Platform operator role is required");
        }
        MutationResult result = transaction.execute(status -> {
            AuthenticatedPlatformOperator actor = authenticator.authenticateForUpdate(rawCredential);
            requireAdministrator(actor.operator());
            List<SensitiveValueHasher.VersionedDigest> identities = requestIdentities(
                    operatorPrincipal(actor), PlatformOperatorOperation.CREATE_OPERATOR,
                    idempotencyKey);
            String digestValue = createDigest(normalizedName, role);
            MutationResult replay = findReplay(identities,
                    PlatformOperatorPrincipalKind.PLATFORM_OPERATOR,
                    actor.operator().getId(), actor.credential().getId(),
                    PlatformOperatorOperation.CREATE_OPERATOR, digestValue);
            if (replay != null) {
                return replay;
            }
            Instant now = clock.instant();
            PlatformOperator operator = operatorRepository.saveAndFlush(
                    new PlatformOperator(normalizedName, role,
                            actor.operator().getId(), now));
            IssuedCredential issued = issueCredential(operator.getId(), now);
            persistRequest(PlatformOperatorPrincipalKind.PLATFORM_OPERATOR,
                    actor.operator().getId(), actor.credential().getId(),
                    actor.credential().getSecretPrefix(),
                    PlatformOperatorOperation.CREATE_OPERATOR, identities.getFirst(),
                    digestValue, operator.getId(), now);
            auditLogService.recordPlatformOperatorAction(actor.context(), requestId,
                    "platform_operator.created", "platform_operator", operator.getId(),
                    "Platform administrator created operator", null,
                    safeOperatorState(operator));
            return MutationResult.created(operator, issued.credential().getSecretPrefix(),
                    issued.rawSecret());
        });
        if (result == null) {
            throw new IllegalStateException("Platform operator creation returned no result");
        }
        return result;
    }

    public MutationResult disable(String rawCredential, String idempotencyKey,
            UUID targetOperatorId, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        if (targetOperatorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Platform operator id is required");
        }
        MutationResult result = transaction.execute(status -> {
            AuthenticatedPlatformOperator actor = authenticator.authenticateForUpdate(rawCredential);
            requireAdministrator(actor.operator());
            List<SensitiveValueHasher.VersionedDigest> identities = requestIdentities(
                    operatorPrincipal(actor), PlatformOperatorOperation.DISABLE_OPERATOR,
                    idempotencyKey);
            String digestValue = disableDigest(targetOperatorId);
            MutationResult replay = findReplay(identities,
                    PlatformOperatorPrincipalKind.PLATFORM_OPERATOR,
                    actor.operator().getId(), actor.credential().getId(),
                    PlatformOperatorOperation.DISABLE_OPERATOR, digestValue);
            if (replay != null) {
                return replay;
            }
            PlatformOperator target = operatorRepository.findByIdForUpdate(targetOperatorId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Platform operator was not found"));
            if (!target.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Platform operator is already disabled");
            }
            PlatformOperatorCredential targetCredential = credentialRepository
                    .findByPlatformOperatorIdForUpdate(targetOperatorId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Platform operator lost credential lineage"));
            Instant now = clock.instant();
            Map<String, Object> before = safeOperatorState(target);
            target.disable(now);
            targetCredential.disable(now);
            persistRequest(PlatformOperatorPrincipalKind.PLATFORM_OPERATOR,
                    actor.operator().getId(), actor.credential().getId(),
                    actor.credential().getSecretPrefix(),
                    PlatformOperatorOperation.DISABLE_OPERATOR, identities.getFirst(),
                    digestValue, target.getId(), now);
            auditLogService.recordPlatformOperatorAction(actor.context(), requestId,
                    "platform_operator.disabled", "platform_operator", target.getId(),
                    "Platform administrator disabled operator", before,
                    safeOperatorState(target));
            return MutationResult.completed(target, targetCredential.getSecretPrefix());
        });
        if (result == null) {
            throw new IllegalStateException("Platform operator disable returned no result");
        }
        return result;
    }

    private MutationResult findReplay(List<SensitiveValueHasher.VersionedDigest> identities,
            PlatformOperatorPrincipalKind principalKind, UUID principalOperatorId,
            UUID principalCredentialId, PlatformOperatorOperation operation,
            String canonicalRequest) {
        List<PlatformOperatorRequest> matches = requestRepository
                .findByRequestIdentityHmacInForUpdate(identities.stream()
                        .map(SensitiveValueHasher.VersionedDigest::digest).toList());
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Platform request identities are ambiguous");
        }
        PlatformOperatorRequest request = matches.getFirst();
        String expectedDigest = sensitiveValueHasher.hmacSha256(
                REQUEST_DIGEST_DOMAIN, canonicalRequest,
                request.getRequestDigestKeyVersion());
        if (request.getPrincipalKind() != principalKind
                || !sameUuid(request.getPrincipalOperatorId(), principalOperatorId)
                || !sameUuid(request.getPrincipalCredentialId(), principalCredentialId)
                || request.getOperation() != operation
                || !constantTimeEquals(request.getRequestDigest(), expectedDigest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different platform request");
        }
        PlatformOperator operator = operatorRepository.findById(request.getResultOperatorId())
                .orElseThrow(() -> new IllegalStateException(
                        "Platform request lost operator result lineage"));
        PlatformOperatorCredential credential = credentialRepository
                .findByPlatformOperatorId(operator.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Platform request lost credential result lineage"));
        return MutationResult.replayed(operator, credential.getSecretPrefix());
    }

    private void persistRequest(PlatformOperatorPrincipalKind principalKind,
            UUID principalOperatorId, UUID principalCredentialId,
            String principalCredentialPrefix, PlatformOperatorOperation operation,
            SensitiveValueHasher.VersionedDigest identity, String canonicalRequest,
            UUID resultOperatorId, Instant now) {
        String digest = sensitiveValueHasher.hmacSha256(
                REQUEST_DIGEST_DOMAIN, canonicalRequest, identity.keyVersion());
        requestRepository.saveAndFlush(new PlatformOperatorRequest(principalKind,
                principalOperatorId, principalCredentialId, principalCredentialPrefix,
                operation, identity.digest(), identity.keyVersion(), digest,
                identity.keyVersion(), resultOperatorId, now));
    }

    private IssuedCredential issueCredential(UUID operatorId, Instant now) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String rawSecret = "thaap_platform_" + HexFormat.of().formatHex(random);
        SensitiveValueHasher.VersionedDigest identity = sensitiveValueHasher
                .hmacSha256Keyring(CREDENTIAL_DOMAIN, rawSecret).getFirst();
        PlatformOperatorCredential credential = credentialRepository.saveAndFlush(
                new PlatformOperatorCredential(operatorId, SecretHasher.prefix(rawSecret),
                        SecretHasher.sha256(rawSecret), identity.digest(),
                        identity.keyVersion(), now));
        return new IssuedCredential(credential, rawSecret);
    }

    private List<SensitiveValueHasher.VersionedDigest> requestIdentities(String principal,
            PlatformOperatorOperation operation, String idempotencyKey) {
        return sensitiveValueHasher.hmacSha256Keyring(REQUEST_IDENTITY_DOMAIN,
                principal + "\n" + operation.name() + "\n" + idempotencyKey);
    }

    private String operatorPrincipal(AuthenticatedPlatformOperator actor) {
        return "PLATFORM_OPERATOR\n" + actor.operator().getId() + "\n"
                + actor.credential().getId();
    }

    private String bootstrapDigest(String displayName) {
        return "displayName=" + displayName + "\nrole=PLATFORM_ADMIN";
    }

    private String createDigest(String displayName, PlatformOperatorRole role) {
        return "displayName=" + displayName + "\nrole=" + role.name();
    }

    private String disableDigest(UUID targetOperatorId) {
        return "targetOperatorId=" + targetOperatorId;
    }

    private Map<String, Object> safeOperatorState(PlatformOperator operator) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("operatorCode", operator.getOperatorCode());
        state.put("role", operator.getRole().name());
        state.put("status", operator.getStatus().name());
        state.put("lifecycleEpoch", operator.getLifecycleEpoch());
        return state;
    }

    private void requireAdministrator(PlatformOperator operator) {
        if (operator.getRole() != PlatformOperatorRole.PLATFORM_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform administrator role is required");
        }
    }

    private void requireBootstrapToken(String providedToken) {
        if (bootstrapToken == null || bootstrapToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform operator bootstrap is disabled");
        }
        if (providedToken == null || providedToken.isBlank()
                || providedToken.length() > 512) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Invalid platform operator bootstrap token");
        }
        String expected = sensitiveValueHasher.hmacSha256(
                "platform.operator.bootstrap", bootstrapToken);
        String provided = sensitiveValueHasher.hmacSha256(
                "platform.operator.bootstrap", providedToken);
        if (!constantTimeEquals(expected, provided)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Invalid platform operator bootstrap token");
        }
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing Idempotency-Key header");
        }
        if (idempotencyKey.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain at most 255 characters");
        }
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Platform operator display name is required");
        }
        String normalized = displayName.trim();
        if (normalized.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Platform operator display name must contain at most 120 characters");
        }
        return normalized;
    }

    private boolean sameUuid(UUID first, UUID second) {
        return first == null ? second == null : first.equals(second);
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private record IssuedCredential(PlatformOperatorCredential credential,
            String rawSecret) {
    }

    public record MutationResult(PlatformOperator operator, String credentialPrefix,
            String credential, boolean credentialRevealed, boolean replayed) {

        static MutationResult created(PlatformOperator operator,
                String credentialPrefix, String credential) {
            return new MutationResult(operator, credentialPrefix, credential, true, false);
        }

        static MutationResult completed(PlatformOperator operator,
                String credentialPrefix) {
            return new MutationResult(operator, credentialPrefix, null, false, false);
        }

        static MutationResult replayed(PlatformOperator operator,
                String credentialPrefix) {
            return new MutationResult(operator, credentialPrefix, null, false, true);
        }
    }
}
