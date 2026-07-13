package com.thaap.payments.review;

import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudDecisionReasonCodeListConverter;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "manual_review_cases", uniqueConstraints = {
        @UniqueConstraint(name = "uk_manual_reviews_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_manual_reviews_attempt", columnNames = {"merchant_id", "payment_attempt_id"}),
        @UniqueConstraint(name = "uk_manual_reviews_decision", columnNames = {"merchant_id", "fraud_decision_id"})
})
public class ManualReviewCase {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Enumerated(EnumType.STRING) @Column(name = "decision_action", nullable = false)
    private FraudDecisionAction decisionAction;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ManualReviewCaseStatus status;
    @Convert(converter = FraudDecisionReasonCodeListConverter.class)
    @Column(name = "reason_codes", nullable = false) private List<FraudDecisionReasonCode> reasonCodes;
    @Enumerated(EnumType.STRING) @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;
    @Enumerated(EnumType.STRING) @Column(name = "cancellation_reason", length = 32)
    private ManualReviewCancellationReason cancellationReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "canceled_at") private Instant canceledAt;

    protected ManualReviewCase() {
    }

    public ManualReviewCase(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId, UUID fraudDecisionId,
            List<FraudDecisionReasonCode> reasonCodes, TrafficOrigin trafficOrigin) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.paymentAttemptId = paymentAttemptId;
        this.fraudDecisionId = fraudDecisionId;
        this.decisionAction = FraudDecisionAction.REVIEW;
        this.status = ManualReviewCaseStatus.OPEN;
        this.reasonCodes = List.copyOf(reasonCodes);
        this.trafficOrigin = trafficOrigin;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public FraudDecisionAction getDecisionAction() { return decisionAction; }
    public ManualReviewCaseStatus getStatus() { return status; }
    public List<FraudDecisionReasonCode> getReasonCodes() { return List.copyOf(reasonCodes); }
    public TrafficOrigin getTrafficOrigin() { return trafficOrigin; }
    public ManualReviewCancellationReason getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCanceledAt() { return canceledAt; }

    public void cancelByMerchant() {
        if (status != ManualReviewCaseStatus.OPEN) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "ManualReviewCase cannot be canceled from status " + status);
        }
        Instant now = Instant.now();
        this.status = ManualReviewCaseStatus.CANCELED;
        this.cancellationReason = ManualReviewCancellationReason.MERCHANT_CANCELED;
        this.canceledAt = now;
        this.updatedAt = now;
    }
}
