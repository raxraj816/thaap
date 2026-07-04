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
import com.thaap.payments.tenant.TrafficOrigin;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Entity
@Table(name = "payment_attempts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_attempts_merchant_id_id", columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_payment_attempts_merchant_id_intent_id",
                        columnNames = {"merchant_id", "id", "payment_intent_id"}),
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

    // Correlation pointer for API reads; durable ownership stays FraudDecision -> PaymentAttempt to avoid circular FKs.
    @Column(name = "fraud_decision_id")
    private UUID fraudDecisionId;

    @Column(name = "processor_authorization_id")
    private String processorAuthorizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "three_ds_status")
    private ThreeDsStatus threeDsStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID merchantId, UUID paymentIntentId, UUID paymentMethodId, String idempotencyKey,
            int attemptNumber) {
        this(merchantId, paymentIntentId, paymentMethodId, idempotencyKey, attemptNumber,
                TrafficOrigin.MERCHANT_API);
    }

    public PaymentAttempt(UUID merchantId, UUID paymentIntentId, UUID paymentMethodId, String idempotencyKey,
            int attemptNumber, TrafficOrigin trafficOrigin) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.paymentMethodId = paymentMethodId;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentAttemptStatus.CREATED;
        this.attemptNumber = attemptNumber;
        this.trafficOrigin = trafficOrigin;
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

    public TrafficOrigin getTrafficOrigin() {
        return trafficOrigin;
    }

    public void attachFraudDecision(UUID fraudDecisionId) {
        if (status != PaymentAttemptStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentAttempt cannot attach fraud decision from status " + status);
        }
        if (this.fraudDecisionId != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PaymentAttempt already has a fraud decision");
        }
        this.fraudDecisionId = fraudDecisionId;
        this.updatedAt = Instant.now();
    }

    public void markReadyForProcessor() {
        if (status != PaymentAttemptStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentAttempt cannot be made processor-ready from status " + status);
        }
        this.status = PaymentAttemptStatus.READY_FOR_PROCESSOR;
        this.updatedAt = Instant.now();
    }

    public void markAuthorized(String processorAuthorizationId) {
        requireProcessorReady("authorized");
        this.status = PaymentAttemptStatus.AUTHORIZED;
        this.processorAuthorizationId = processorAuthorizationId;
        this.updatedAt = Instant.now();
    }

    public void markDeclined(String processorAuthorizationId) {
        requireProcessorReady("declined");
        this.status = PaymentAttemptStatus.DECLINED;
        this.processorAuthorizationId = processorAuthorizationId;
        this.updatedAt = Instant.now();
    }

    public void markRequiresAction(String processorAuthorizationId) {
        requireProcessorReady("action-required");
        this.status = PaymentAttemptStatus.REQUIRES_ACTION;
        this.processorAuthorizationId = processorAuthorizationId;
        this.threeDsStatus = ThreeDsStatus.REQUESTED;
        this.updatedAt = Instant.now();
    }

    private void requireProcessorReady(String targetStatus) {
        if (status != PaymentAttemptStatus.READY_FOR_PROCESSOR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentAttempt cannot be marked " + targetStatus + " from status " + status);
        }
    }
}
