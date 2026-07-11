package com.thaap.payments.merchant;

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
@Table(name = "merchant_creation_records",
        indexes = @Index(name = "idx_merchant_creation_status",
                columnList = "status,created_at,id"))
public class MerchantCreationRecord {

    @Id
    private UUID id;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, unique = true)
    private String idempotencyKeyHash;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "derivation_key_version", nullable = false, length = 32)
    private String derivationKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MerchantCreationStatus status;

    @Column(name = "merchant_id", unique = true)
    private UUID merchantId;

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

    protected MerchantCreationRecord() {
    }

    public MerchantCreationRecord(String idempotencyKeyHash, String requestHash,
            String derivationKeyVersion,
            UUID leaseToken, Instant leaseExpiresAt, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestHash = requestHash;
        this.derivationKeyVersion = derivationKeyVersion;
        this.status = MerchantCreationStatus.IN_PROGRESS;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public void reclaim(UUID newLeaseToken, Instant now, Instant newLeaseExpiresAt) {
        if (status != MerchantCreationStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only an in-progress merchant creation can be reclaimed");
        }
        if (hasActiveLease(now)) {
            throw new IllegalStateException("Active merchant creation reservation cannot be reclaimed");
        }
        this.leaseToken = newLeaseToken;
        this.leaseExpiresAt = newLeaseExpiresAt;
        this.updatedAt = now;
    }

    public void complete(UUID expectedLeaseToken, UUID completedMerchantId,
            Instant now, Instant replayExpiresAt) {
        requireOwnedInProgress(expectedLeaseToken);
        this.merchantId = completedMerchantId;
        this.status = MerchantCreationStatus.COMPLETED;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        this.expiresAt = replayExpiresAt;
    }

    public void requireOwnedInProgress(UUID expectedLeaseToken) {
        if (status != MerchantCreationStatus.IN_PROGRESS
                || expectedLeaseToken == null
                || !expectedLeaseToken.equals(leaseToken)) {
            throw new IllegalStateException("Merchant creation reservation ownership changed");
        }
    }

    public boolean hasActiveLease(Instant now) {
        return status == MerchantCreationStatus.IN_PROGRESS
                && leaseToken != null
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isOwnedInProgress(UUID expectedLeaseToken) {
        return status == MerchantCreationStatus.IN_PROGRESS
                && expectedLeaseToken != null
                && expectedLeaseToken.equals(leaseToken);
    }

    public UUID getId() {
        return id;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public String getDerivationKeyVersion() {
        return derivationKeyVersion;
    }

    public MerchantCreationStatus getStatus() {
        return status;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
