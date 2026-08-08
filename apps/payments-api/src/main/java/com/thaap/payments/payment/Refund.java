package com.thaap.payments.payment;

import com.thaap.payments.time.DatabaseInstant;
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
@Table(name = "refunds",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_refunds_merchant_id_id",
                        columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_refunds_merchant_id_id_payment_intent",
                        columnNames = {"merchant_id", "id", "payment_intent_id"}),
                @UniqueConstraint(name = "uk_refunds_payment_intent",
                        columnNames = {"merchant_id", "payment_intent_id"})
        },
        indexes = {
                @Index(name = "idx_refunds_merchant_created",
                        columnList = "merchant_id,created_at,id"),
                @Index(name = "idx_refunds_attempt",
                        columnList = "merchant_id,payment_attempt_id")
        })
public class Refund {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(name = "authorization_id", nullable = false)
    private UUID authorizationId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundStatus status;

    @Column(name = "reason_catalog_version", nullable = false)
    private int reasonCatalogVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 64)
    private RefundReasonCode reasonCode;

    @Column(name = "source_review_resolution_id")
    private UUID sourceReviewResolutionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {
    }

    public Refund(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId, UUID authorizationId,
            long amount, String currency) {
        this(merchantId, paymentIntentId, paymentAttemptId, authorizationId, amount, currency,
                RefundReasonCode.MERCHANT_REQUESTED, null);
    }

    public Refund(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId, UUID authorizationId,
            long amount, String currency, RefundReasonCode reasonCode, UUID sourceReviewResolutionId) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.paymentAttemptId = paymentAttemptId;
        this.authorizationId = authorizationId;
        this.amount = amount;
        this.currency = currency;
        this.status = RefundStatus.RECORDED;
        this.reasonCatalogVersion = 1;
        this.reasonCode = reasonCode;
        this.sourceReviewResolutionId = sourceReviewResolutionId;
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getPaymentIntentId() {
        return paymentIntentId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public int getReasonCatalogVersion() { return reasonCatalogVersion; }

    public RefundReasonCode getReasonCode() { return reasonCode; }

    public UUID getSourceReviewResolutionId() { return sourceReviewResolutionId; }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
