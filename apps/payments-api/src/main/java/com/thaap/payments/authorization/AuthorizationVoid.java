package com.thaap.payments.authorization;

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
@Table(name = "authorization_voids",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_authorization_voids_merchant_id_id",
                        columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_authorization_voids_authorization",
                        columnNames = {"merchant_id", "authorization_id"}),
                @UniqueConstraint(name = "uk_authorization_voids_payment_intent",
                        columnNames = {"merchant_id", "payment_intent_id"})
        },
        indexes = {
                @Index(name = "idx_authorization_voids_merchant_created",
                        columnList = "merchant_id,created_at,id"),
                @Index(name = "idx_authorization_voids_attempt",
                        columnList = "merchant_id,payment_attempt_id")
        })
public class AuthorizationVoid {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "authorization_id", nullable = false)
    private UUID authorizationId;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuthorizationVoidStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuthorizationVoidReason reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthorizationVoid() {
    }

    public AuthorizationVoid(UUID merchantId, UUID authorizationId, UUID paymentAttemptId, UUID paymentIntentId) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.authorizationId = authorizationId;
        this.paymentAttemptId = paymentAttemptId;
        this.paymentIntentId = paymentIntentId;
        this.status = AuthorizationVoidStatus.RECORDED;
        this.reason = AuthorizationVoidReason.MERCHANT_CANCELED;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public UUID getPaymentIntentId() {
        return paymentIntentId;
    }

    public AuthorizationVoidStatus getStatus() {
        return status;
    }

    public AuthorizationVoidReason getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
