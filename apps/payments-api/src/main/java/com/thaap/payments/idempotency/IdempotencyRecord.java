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
import java.util.UUID;

@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_merchant_key", columnNames = {"merchant_id", "idempotency_key"}),
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

    @Column(nullable = false)
    private String operation;

    @Column(nullable = false)
    private String requestHash;

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

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID merchantId, String key, String operation, String requestHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.key = key;
        this.operation = operation;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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

    public String getOperation() {
        return operation;
    }

    public String getRequestHash() {
        return requestHash;
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

    public void complete(int httpStatus, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.updatedAt = Instant.now();
    }
}
