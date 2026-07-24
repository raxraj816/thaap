package com.thaap.payments.review;

import com.thaap.payments.risk.RiskOperatorRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_resolutions")
public class ReviewResolution {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "manual_review_case_id", nullable = false) private UUID manualReviewCaseId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Column(name = "actor_operator_id", nullable = false) private UUID actorOperatorId;
    @Enumerated(EnumType.STRING) @Column(name = "actor_role", nullable = false, length = 32)
    private RiskOperatorRole actorRole;
    @Enumerated(EnumType.STRING) @Column(name = "review_stage", nullable = false, length = 32)
    private ReviewStage reviewStage;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64)
    private ReviewResolutionOutcome outcome;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private ReviewClassification classification;
    @Enumerated(EnumType.STRING) @Column(name = "fraud_type", length = 64) private FraudType fraudType;
    @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 64)
    private ReviewResolutionReasonCode reasonCode;
    @Column(name = "resolved_at", nullable = false) private Instant resolvedAt;

    protected ReviewResolution() {
    }

    public ReviewResolution(ManualReviewCase reviewCase, UUID actorOperatorId, RiskOperatorRole actorRole,
            ReviewResolutionOutcome outcome, ReviewClassification classification, FraudType fraudType,
            ReviewResolutionReasonCode reasonCode, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = reviewCase.getMerchantId();
        this.manualReviewCaseId = reviewCase.getId();
        this.paymentIntentId = reviewCase.getPaymentIntentId();
        this.paymentAttemptId = reviewCase.getPaymentAttemptId();
        this.fraudDecisionId = reviewCase.getFraudDecisionId();
        this.actorOperatorId = actorOperatorId;
        this.actorRole = actorRole;
        this.reviewStage = reviewCase.getReviewStage();
        this.outcome = outcome;
        this.classification = classification;
        this.fraudType = fraudType;
        this.reasonCode = reasonCode;
        this.resolvedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getManualReviewCaseId() { return manualReviewCaseId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public ReviewResolutionOutcome getOutcome() { return outcome; }
    public ReviewStage getReviewStage() { return reviewStage; }
    public ReviewClassification getClassification() { return classification; }
    public FraudType getFraudType() { return fraudType; }
    public ReviewResolutionReasonCode getReasonCode() { return reasonCode; }
    public Instant getResolvedAt() { return resolvedAt; }
}
