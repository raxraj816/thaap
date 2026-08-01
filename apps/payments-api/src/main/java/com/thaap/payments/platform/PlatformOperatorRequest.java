package com.thaap.payments.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "platform_operator_requests")
public class PlatformOperatorRequest {

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "principal_kind", nullable = false, length = 24)
    private PlatformOperatorPrincipalKind principalKind;
    @Column(name = "principal_operator_id")
    private UUID principalOperatorId;
    @Column(name = "principal_credential_id")
    private UUID principalCredentialId;
    @Column(name = "principal_credential_prefix", length = 32)
    private String principalCredentialPrefix;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private PlatformOperatorOperation operation;
    @Column(name = "request_identity_hmac", nullable = false, length = 64)
    private String requestIdentityHmac;
    @Column(name = "request_identity_key_version", nullable = false, length = 32)
    private String requestIdentityKeyVersion;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @Column(name = "request_digest_key_version", nullable = false, length = 32)
    private String requestDigestKeyVersion;
    @Column(name = "lifecycle_status", nullable = false, length = 16)
    private String lifecycleStatus;
    @Column(name = "result_operator_id", nullable = false)
    private UUID resultOperatorId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected PlatformOperatorRequest() {
    }

    public PlatformOperatorRequest(PlatformOperatorPrincipalKind principalKind,
            UUID principalOperatorId, UUID principalCredentialId,
            String principalCredentialPrefix, PlatformOperatorOperation operation,
            String requestIdentityHmac, String requestIdentityKeyVersion,
            String requestDigest, String requestDigestKeyVersion,
            UUID resultOperatorId, Instant now) {
        this.id = UUID.randomUUID();
        this.principalKind = Objects.requireNonNull(principalKind, "principalKind");
        this.principalOperatorId = principalOperatorId;
        this.principalCredentialId = principalCredentialId;
        this.principalCredentialPrefix = principalCredentialPrefix;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.requestIdentityHmac = Objects.requireNonNull(requestIdentityHmac, "requestIdentityHmac");
        this.requestIdentityKeyVersion = Objects.requireNonNull(
                requestIdentityKeyVersion, "requestIdentityKeyVersion");
        this.requestDigest = Objects.requireNonNull(requestDigest, "requestDigest");
        this.requestDigestKeyVersion = Objects.requireNonNull(
                requestDigestKeyVersion, "requestDigestKeyVersion");
        this.lifecycleStatus = "COMPLETED";
        this.resultOperatorId = Objects.requireNonNull(resultOperatorId, "resultOperatorId");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.completedAt = now;
    }

    public PlatformOperatorPrincipalKind getPrincipalKind() { return principalKind; }
    public UUID getPrincipalOperatorId() { return principalOperatorId; }
    public UUID getPrincipalCredentialId() { return principalCredentialId; }
    public PlatformOperatorOperation getOperation() { return operation; }
    public String getRequestIdentityKeyVersion() { return requestIdentityKeyVersion; }
    public String getRequestDigest() { return requestDigest; }
    public String getRequestDigestKeyVersion() { return requestDigestKeyVersion; }
    public UUID getResultOperatorId() { return resultOperatorId; }
}
