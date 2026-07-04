package com.thaap.payments.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoint_creation_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_webhook_endpoint_creation_merchant_key",
                        columnNames = {"merchant_id", "idempotency_key_hash"}),
                @UniqueConstraint(name = "uk_webhook_endpoint_creation_endpoint",
                        columnNames = {"merchant_id", "webhook_endpoint_id"})
        },
        indexes = @Index(name = "idx_webhook_endpoint_creation_merchant_status",
                columnList = "merchant_id,status,created_at,id"))
public class WebhookEndpointCreationRecord {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WebhookEndpointCreationStatus status;

    @Column(name = "webhook_endpoint_id")
    private UUID webhookEndpointId;

    @Column(name = "signing_secret_ciphertext", columnDefinition = "TEXT")
    private String signingSecretCiphertext;

    @Column(name = "signing_secret_nonce", length = 64)
    private String signingSecretNonce;

    @Column(name = "signing_secret_key_version", length = 32)
    private String signingSecretKeyVersion;

    @Column(name = "signing_secret_generation")
    private UUID signingSecretGeneration;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected WebhookEndpointCreationRecord() {
    }

    public WebhookEndpointCreationRecord(UUID merchantId, String idempotencyKeyHash, String requestHash,
            UUID leaseToken, Instant leaseExpiresAt, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestHash = requestHash;
        this.status = WebhookEndpointCreationStatus.IN_PROGRESS;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public void complete(UUID expectedLeaseToken, UUID endpointId, UUID secretGeneration,
            WebhookEncryptedSecret encryptedSecret, Instant now, Instant replayExpiresAt) {
        if (status != WebhookEndpointCreationStatus.IN_PROGRESS) {
            throw new IllegalStateException("Webhook endpoint creation is already complete");
        }
        if (!expectedLeaseToken.equals(leaseToken)) {
            throw new IllegalStateException("Webhook endpoint creation reservation ownership changed");
        }
        this.webhookEndpointId = endpointId;
        this.signingSecretCiphertext = encryptedSecret.ciphertext();
        this.signingSecretNonce = encryptedSecret.nonce();
        this.signingSecretKeyVersion = encryptedSecret.keyVersion();
        this.signingSecretGeneration = secretGeneration;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.status = WebhookEndpointCreationStatus.COMPLETED;
        this.updatedAt = now;
        this.expiresAt = replayExpiresAt;
    }

    public void reclaim(UUID newLeaseToken, Instant now, Instant newLeaseExpiresAt) {
        if (status != WebhookEndpointCreationStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only an in-progress endpoint creation can be reclaimed");
        }
        if (leaseExpiresAt != null && leaseExpiresAt.isAfter(now)) {
            throw new IllegalStateException("Active endpoint creation reservation cannot be reclaimed");
        }
        this.leaseToken = newLeaseToken;
        this.leaseExpiresAt = newLeaseExpiresAt;
        this.updatedAt = now;
    }

    public boolean hasActiveLease(Instant now) {
        return status == WebhookEndpointCreationStatus.IN_PROGRESS
                && leaseToken != null
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }

    public boolean isOwnedInProgress(UUID expectedLeaseToken) {
        return status == WebhookEndpointCreationStatus.IN_PROGRESS
                && expectedLeaseToken != null
                && expectedLeaseToken.equals(leaseToken);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public WebhookEndpointCreationStatus getStatus() {
        return status;
    }

    public UUID getWebhookEndpointId() {
        return webhookEndpointId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getSigningSecretGeneration() {
        return signingSecretGeneration;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public WebhookEncryptedSecret encryptedSecret() {
        if (signingSecretCiphertext == null || signingSecretNonce == null || signingSecretKeyVersion == null) {
            throw new IllegalStateException("Completed endpoint creation has no encrypted secret response");
        }
        return new WebhookEncryptedSecret(
                signingSecretCiphertext, signingSecretNonce, signingSecretKeyVersion);
    }
}
