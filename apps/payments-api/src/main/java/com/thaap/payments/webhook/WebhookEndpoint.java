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
        this.createdAt = createdAt;
    }

    public void rotateSecret(String rawSigningSecret, WebhookEncryptedSecret encryptedSecret) {
        this.signingSecretPrefix = SecretHasher.prefix(rawSigningSecret);
        this.signingSecretHash = SecretHasher.sha256(rawSigningSecret);
        replaceEncryptedSecret(encryptedSecret);
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
