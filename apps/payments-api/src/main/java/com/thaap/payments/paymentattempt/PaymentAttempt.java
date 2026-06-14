package com.thaap.payments.paymentattempt;

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
@Table(name = "payment_attempts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_attempts_merchant_id_id", columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_payment_attempts_intent_number",
                        columnNames = {"merchant_id", "payment_intent_id", "attempt_number"})
        },
        indexes = {
                @Index(name = "idx_payment_attempts_merchant", columnList = "merchant_id"),
                @Index(name = "idx_payment_attempts_payment_intent", columnList = "payment_intent_id"),
                @Index(name = "idx_payment_attempts_payment_method", columnList = "payment_method_id"),
                @Index(name = "idx_payment_attempts_merchant_created", columnList = "merchant_id,created_at,id")
        })
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;

    @Column(name = "payment_method_id", nullable = false)
    private UUID paymentMethodId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttemptStatus status;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(name = "fraud_decision_id")
    private UUID fraudDecisionId;

    @Column(name = "processor_authorization_id")
    private String processorAuthorizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "three_ds_status")
    private ThreeDsStatus threeDsStatus;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID merchantId, UUID paymentIntentId, UUID paymentMethodId, String idempotencyKey,
            int attemptNumber) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.paymentMethodId = paymentMethodId;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentAttemptStatus.CREATED;
        this.attemptNumber = attemptNumber;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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

    public UUID getPaymentMethodId() {
        return paymentMethodId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public UUID getFraudDecisionId() {
        return fraudDecisionId;
    }

    public String getProcessorAuthorizationId() {
        return processorAuthorizationId;
    }

    public ThreeDsStatus getThreeDsStatus() {
        return threeDsStatus;
    }
}
