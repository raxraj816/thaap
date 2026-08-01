package com.thaap.payments.platform;

import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PlatformOperatorAuthenticator {

    private static final String CREDENTIAL_DOMAIN = "platform.operator.credential";

    private final PlatformOperatorRepository operatorRepository;
    private final PlatformOperatorCredentialRepository credentialRepository;
    private final SensitiveValueHasher sensitiveValueHasher;

    public PlatformOperatorAuthenticator(PlatformOperatorRepository operatorRepository,
            PlatformOperatorCredentialRepository credentialRepository,
            SensitiveValueHasher sensitiveValueHasher) {
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
    }

    public AuthenticatedPlatformOperator authenticate(String rawKey) {
        PlatformOperatorCredential candidate = locateCredential(rawKey);
        PlatformOperator operator = operatorRepository.findById(candidate.getPlatformOperatorId())
                .orElseThrow(this::invalidCredential);
        requireLiveCredential(rawKey, operator, candidate);
        return new AuthenticatedPlatformOperator(operator, candidate);
    }

    public PlatformOperatorContext authenticateContext(String rawKey) {
        return authenticate(rawKey).context();
    }

    /** Caller must own a transaction. The operator is locked before the credential. */
    public AuthenticatedPlatformOperator authenticateForUpdate(String rawKey) {
        PlatformOperatorCredential candidate = locateCredential(rawKey);
        PlatformOperator operator = operatorRepository.findByIdForUpdate(
                        candidate.getPlatformOperatorId())
                .orElseThrow(this::invalidCredential);
        PlatformOperatorCredential lockedCredential = credentialRepository.findByIdForUpdate(
                        candidate.getId())
                .orElseThrow(this::invalidCredential);
        requireLiveCredential(rawKey, operator, lockedCredential);
        return new AuthenticatedPlatformOperator(operator, lockedCredential);
    }

    /** Caller must own a transaction. The operator row remains the first lock. */
    public PlatformOperatorContext authenticateContextForUpdate(String rawKey) {
        return authenticateForUpdate(rawKey).context();
    }

    private PlatformOperatorCredential locateCredential(String rawKey) {
        requireKeyShape(rawKey);
        List<String> identities = sensitiveValueHasher.hmacSha256Keyring(
                        CREDENTIAL_DOMAIN, rawKey)
                .stream().map(SensitiveValueHasher.VersionedDigest::digest).toList();
        List<PlatformOperatorCredential> matches =
                credentialRepository.findBySecretIdentityHmacIn(identities);
        if (matches.size() != 1) {
            throw invalidCredential();
        }
        return matches.getFirst();
    }

    private void requireLiveCredential(String rawKey, PlatformOperator operator,
            PlatformOperatorCredential credential) {
        if (!operator.isActive() || !credential.isActive()
                || !operator.getId().equals(credential.getPlatformOperatorId())
                || !constantTimeEquals(credential.getSecretSha256(), SecretHasher.sha256(rawKey))) {
            throw invalidCredential();
        }
        String expectedIdentity = sensitiveValueHasher.hmacSha256(
                CREDENTIAL_DOMAIN, rawKey, credential.getSecretHmacKeyVersion());
        if (!constantTimeEquals(credential.getSecretIdentityHmac(), expectedIdentity)) {
            throw invalidCredential();
        }
    }

    private void requireKeyShape(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > 256) {
            throw invalidCredential();
        }
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private ResponseStatusException invalidCredential() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid platform operator credential");
    }
}
