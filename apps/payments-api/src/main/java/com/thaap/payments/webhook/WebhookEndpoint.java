package com.thaap.payments.webhook;

import com.thaap.payments.security.SecretHasher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints", indexes = {
        @Index(name = "idx_webhook_endpoints_merchant", columnList = "merchant_id"),
        @Index(name = "idx_webhook_endpoints_merchant_status",
                columnList = "merchant_id, operational_status, created_at, id")
})
public class WebhookEndpoint {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 32)
    private WebhookEndpointStatus operationalStatus;

    @Column(nullable = false)
    private String signingSecretPrefix;

    @Column(nullable = false)
    private String signingSecretHash;

    @Column(name = "signing_secret_ciphertext", columnDefinition = "TEXT")
    private String signingSecretCiphertext;

    @Column(name = "signing_secret_nonce", length = 64)
    private String signingSecretNonce;

    @Column(name = "signing_secret_key_version", length = 32)
    private String signingSecretKeyVersion;

    @Column(name = "signing_secret_generation", nullable = false)
    private UUID signingSecretGeneration;

    @Column(name = "previous_signing_secret_prefix", length = 32)
    private String previousSigningSecretPrefix;

    @Column(name = "previous_signing_secret_hash", length = 64)
    private String previousSigningSecretHash;

    @Column(name = "previous_signing_secret_ciphertext", columnDefinition = "TEXT")
    private String previousSigningSecretCiphertext;

    @Column(name = "previous_signing_secret_nonce", length = 64)
    private String previousSigningSecretNonce;

    @Column(name = "previous_signing_secret_key_version", length = 32)
    private String previousSigningSecretKeyVersion;

    @Column(name = "previous_signing_secret_generation")
    private UUID previousSigningSecretGeneration;

    @Column(name = "previous_signing_secret_expires_at")
    private Instant previousSigningSecretExpiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected WebhookEndpoint() {
    }

    public WebhookEndpoint(UUID merchantId, String url) {
        String rawSigningSecret = WebhookSigningSecret.generate();
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.url = url;
        this.operationalStatus = WebhookEndpointStatus.REQUIRES_SECRET_ROTATION;
        this.signingSecretPrefix = SecretHasher.prefix(rawSigningSecret);
        this.signingSecretHash = SecretHasher.sha256(rawSigningSecret);
        this.signingSecretGeneration = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public WebhookEndpoint(UUID id, UUID merchantId, String url, String rawSigningSecret,
            WebhookEncryptedSecret encryptedSecret, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.url = url;
        this.operationalStatus = WebhookEndpointStatus.ACTIVE;
        this.signingSecretPrefix = SecretHasher.prefix(rawSigningSecret);
        this.signingSecretHash = SecretHasher.sha256(rawSigningSecret);
        this.signingSecretCiphertext = encryptedSecret.ciphertext();
        this.signingSecretNonce = encryptedSecret.nonce();
        this.signingSecretKeyVersion = encryptedSecret.keyVersion();
        this.signingSecretGeneration = UUID.randomUUID();
        this.createdAt = createdAt;
    }

    public void rotateSecret(String rawSigningSecret, WebhookEncryptedSecret encryptedSecret,
            Instant rotatedAt, Instant previousExpiresAt) {
        if (hasActivePreviousSecret(rotatedAt)) {
            throw new IllegalStateException(
                    "Webhook signing secret cannot rotate while a previous-secret overlap is active");
        }
        if (this.signingSecretCiphertext != null) {
            this.previousSigningSecretPrefix = this.signingSecretPrefix;
            this.previousSigningSecretHash = this.signingSecretHash;
            this.previousSigningSecretCiphertext = this.signingSecretCiphertext;
            this.previousSigningSecretNonce = this.signingSecretNonce;
            this.previousSigningSecretKeyVersion = this.signingSecretKeyVersion;
            this.previousSigningSecretGeneration = this.signingSecretGeneration;
            this.previousSigningSecretExpiresAt = previousExpiresAt;
        } else {
            clearPreviousSecret();
        }
        this.signingSecretPrefix = SecretHasher.prefix(rawSigningSecret);
        this.signingSecretHash = SecretHasher.sha256(rawSigningSecret);
        replaceEncryptedSecret(encryptedSecret);
        this.signingSecretGeneration = UUID.randomUUID();
        this.operationalStatus = WebhookEndpointStatus.ACTIVE;
    }

    public void reencryptSecret(WebhookEncryptedSecret encryptedSecret) {
        replaceEncryptedSecret(encryptedSecret);
    }

    private void replaceEncryptedSecret(WebhookEncryptedSecret encryptedSecret) {
        this.signingSecretCiphertext = encryptedSecret.ciphertext();
        this.signingSecretNonce = encryptedSecret.nonce();
        this.signingSecretKeyVersion = encryptedSecret.keyVersion();
    }

    private void clearPreviousSecret() {
        this.previousSigningSecretPrefix = null;
        this.previousSigningSecretHash = null;
        this.previousSigningSecretCiphertext = null;
        this.previousSigningSecretNonce = null;
        this.previousSigningSecretKeyVersion = null;
        this.previousSigningSecretGeneration = null;
        this.previousSigningSecretExpiresAt = null;
    }

    public void disable() {
        this.operationalStatus = WebhookEndpointStatus.DISABLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public boolean isActive() {
        return operationalStatus == WebhookEndpointStatus.ACTIVE;
    }

    public WebhookEndpointStatus getOperationalStatus() {
        return operationalStatus;
    }

    public String getSigningSecretPrefix() {
        return signingSecretPrefix;
    }

    public String getSigningSecretHash() {
        return signingSecretHash;
    }

    public String getSigningSecretCiphertext() {
        return signingSecretCiphertext;
    }

    public String getSigningSecretNonce() {
        return signingSecretNonce;
    }

    public String getSigningSecretKeyVersion() {
        return signingSecretKeyVersion;
    }

    public UUID getSigningSecretGeneration() {
        return signingSecretGeneration;
    }

    public String getPreviousSigningSecretPrefix() {
        return previousSigningSecretPrefix;
    }

    public String getPreviousSigningSecretHash() {
        return previousSigningSecretHash;
    }

    public String getPreviousSigningSecretCiphertext() {
        return previousSigningSecretCiphertext;
    }

    public String getPreviousSigningSecretNonce() {
        return previousSigningSecretNonce;
    }

    public String getPreviousSigningSecretKeyVersion() {
        return previousSigningSecretKeyVersion;
    }

    public UUID getPreviousSigningSecretGeneration() {
        return previousSigningSecretGeneration;
    }

    public Instant getPreviousSigningSecretExpiresAt() {
        return previousSigningSecretExpiresAt;
    }

    public boolean hasActivePreviousSecret(Instant now) {
        return previousSigningSecretGeneration != null
                && previousSigningSecretExpiresAt != null
                && previousSigningSecretExpiresAt.isAfter(now);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
