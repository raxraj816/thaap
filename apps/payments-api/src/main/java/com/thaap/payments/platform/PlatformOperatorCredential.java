package com.thaap.payments.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "platform_operator_credentials")
public class PlatformOperatorCredential {

    @Id
    private UUID id;
    @Column(name = "platform_operator_id", nullable = false)
    private UUID platformOperatorId;
    @Column(name = "secret_prefix", nullable = false, length = 32)
    private String secretPrefix;
    @Column(name = "secret_sha256", nullable = false, length = 64)
    private String secretSha256;
    @Column(name = "secret_identity_hmac", nullable = false, length = 64)
    private String secretIdentityHmac;
    @Column(name = "secret_hmac_key_version", nullable = false, length = 32)
    private String secretHmacKeyVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "disabled_at")
    private Instant disabledAt;

    protected PlatformOperatorCredential() {
    }

    public PlatformOperatorCredential(UUID platformOperatorId, String secretPrefix,
            String secretSha256, String secretIdentityHmac,
            String secretHmacKeyVersion, Instant now) {
        this.id = UUID.randomUUID();
        this.platformOperatorId = Objects.requireNonNull(platformOperatorId, "platformOperatorId");
        this.secretPrefix = Objects.requireNonNull(secretPrefix, "secretPrefix");
        this.secretSha256 = Objects.requireNonNull(secretSha256, "secretSha256");
        this.secretIdentityHmac = Objects.requireNonNull(secretIdentityHmac, "secretIdentityHmac");
        this.secretHmacKeyVersion = Objects.requireNonNull(secretHmacKeyVersion, "secretHmacKeyVersion");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public void disable(Instant now) {
        if (disabledAt == null) {
            disabledAt = Objects.requireNonNull(now, "now");
        }
    }

    public UUID getId() { return id; }
    public UUID getPlatformOperatorId() { return platformOperatorId; }
    public String getSecretPrefix() { return secretPrefix; }
    public String getSecretSha256() { return secretSha256; }
    public String getSecretIdentityHmac() { return secretIdentityHmac; }
    public String getSecretHmacKeyVersion() { return secretHmacKeyVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public boolean isActive() { return disabledAt == null; }
}
