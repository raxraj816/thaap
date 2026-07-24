package com.thaap.payments.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_principal_key", columnNames = {
                "merchant_id", "principal_type", "principal_id", "principal_credential_prefix", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_idempotency_merchant", columnList = "merchant_id"),
                @Index(name = "idx_idempotency_merchant_status", columnList = "merchant_id,status")
        })
public class IdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key", nullable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 32)
    private IdempotencyPrincipalType principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "principal_credential_prefix", nullable = false, length = 32)
    private String principalCredentialPrefix;

    @Column(nullable = false)
    private String operation;

    @Column(nullable = false)
    private String requestHash;

    @Column(name = "request_hash_key_version", nullable = false, length = 32)
    private String requestHashKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    @Column
    private Integer httpStatus;

    @Column(columnDefinition = "text")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID merchantId, String key, String operation, String requestHash, Instant expiresAt) {
        this(merchantId, key, operation, requestHash, "v1", UUID.randomUUID(),
                Instant.now().plus(Duration.ofSeconds(30)), Instant.now(), expiresAt);
    }

    public IdempotencyRecord(UUID merchantId, String key, String operation, String requestHash,
            UUID leaseToken, Instant leaseExpiresAt, Instant now, Instant expiresAt) {
        this(merchantId, key, operation, requestHash, "v1",
                leaseToken, leaseExpiresAt, now, expiresAt);
    }

    public IdempotencyRecord(UUID merchantId, String key, String operation, String requestHash,
            String requestHashKeyVersion, UUID leaseToken, Instant leaseExpiresAt,
            Instant now, Instant expiresAt) {
        this(new IdempotencyPrincipal(merchantId, IdempotencyPrincipalType.MERCHANT_API,
                        merchantId, "test-merchant"),
                key, operation, requestHash, requestHashKeyVersion,
                leaseToken, leaseExpiresAt, now, expiresAt);
    }

    IdempotencyRecord(IdempotencyPrincipal principal, String key, String operation, String requestHash,
            String requestHashKeyVersion, UUID leaseToken, Instant leaseExpiresAt,
            Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.merchantId = principal.merchantId();
        this.key = key;
        this.principalType = principal.type();
        this.principalId = principal.principalId();
        this.principalCredentialPrefix = principal.credentialPrefix();
        this.operation = operation;
        this.requestHash = requestHash;
        this.requestHashKeyVersion = requestHashKeyVersion;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getKey() {
        return key;
    }

    public IdempotencyPrincipalType getPrincipalType() { return principalType; }
    public UUID getPrincipalId() { return principalId; }
    public String getPrincipalCredentialPrefix() { return principalCredentialPrefix; }

    public String getOperation() {
        return operation;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getRequestHashKeyVersion() {
        return requestHashKeyVersion;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public boolean hasActiveLease(Instant now) {
        return status == IdempotencyStatus.IN_PROGRESS
                && leaseToken != null
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void restart(String newOperation, String newRequestHash,
            String newRequestHashKeyVersion, UUID newLeaseToken,
            Instant now, Instant newLeaseExpiresAt, Instant newExpiresAt) {
        if (!isExpired(now)) {
            throw new IllegalStateException(
                    "An unexpired idempotency record cannot be restarted");
        }
        this.operation = newOperation;
        this.requestHash = newRequestHash;
        this.requestHashKeyVersion = newRequestHashKeyVersion;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.httpStatus = null;
        this.responseBody = null;
        this.leaseToken = newLeaseToken;
        this.leaseExpiresAt = newLeaseExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = newExpiresAt;
    }

    public boolean isOwnedInProgress(UUID expectedLeaseToken) {
        return status == IdempotencyStatus.IN_PROGRESS
                && expectedLeaseToken != null
                && expectedLeaseToken.equals(leaseToken);
    }

    public void reclaim(UUID newLeaseToken, Instant now, Instant newLeaseExpiresAt) {
        if (status != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only an in-progress idempotency reservation can be reclaimed");
        }
        if (hasActiveLease(now)) {
            throw new IllegalStateException("Active idempotency reservation cannot be reclaimed");
        }
        this.leaseToken = newLeaseToken;
        this.leaseExpiresAt = newLeaseExpiresAt;
        this.updatedAt = now;
    }

    public void requireOwnedInProgress(UUID expectedLeaseToken) {
        if (!isOwnedInProgress(expectedLeaseToken)) {
            throw new IllegalStateException("Idempotency reservation ownership changed");
        }
    }

    public void complete(UUID expectedLeaseToken, int httpStatus, String responseBody,
            Instant now, Instant replayExpiresAt) {
        requireOwnedInProgress(expectedLeaseToken);
        this.status = IdempotencyStatus.COMPLETED;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        this.expiresAt = replayExpiresAt;
    }
}
