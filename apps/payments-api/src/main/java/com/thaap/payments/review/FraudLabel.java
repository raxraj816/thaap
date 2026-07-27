package com.thaap.payments.review;

import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.thaap.payments.dispute.Chargeback;

@Entity
@Table(name = "fraud_labels")
public class FraudLabel {
    private static final String ACTIVE_TAXONOMY_VERSION = "fraud_taxonomy_v1";

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Enumerated(EnumType.STRING) @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;
    @Column(nullable = false, length = 32) private String source;
    @Enumerated(EnumType.STRING) @Column(name = "label_value", nullable = false, length = 16)
    private FraudLabelValue labelValue;
    @Enumerated(EnumType.STRING) @Column(name = "fraud_type", length = 64)
    private FraudType fraudType;
    @Column(name = "taxonomy_version", length = 32) private String taxonomyVersion;
    @Column(name = "evidence_occurred_at", nullable = false) private Instant evidenceOccurredAt;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "source_review_resolution_id") private UUID sourceReviewResolutionId;
    @Column(name = "source_chargeback_id") private UUID sourceChargebackId;
    @Column(name = "source_post_authorization_signal_id") private UUID sourcePostAuthorizationSignalId;
    @Column(name = "actor_operator_id") private UUID actorOperatorId;
    @Column(name = "supersedes_label_id") private UUID supersedesLabelId;
    @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 64)
    private ReviewResolutionReasonCode reasonCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected FraudLabel() {
    }

    public FraudLabel(ManualReviewCase reviewCase, ReviewResolution resolution, UUID actorOperatorId,
            Instant evidenceOccurredAt, Instant availableAt) {
        this.id = UUID.randomUUID();
        this.merchantId = reviewCase.getMerchantId();
        this.paymentIntentId = reviewCase.getPaymentIntentId();
        this.paymentAttemptId = reviewCase.getPaymentAttemptId();
        this.fraudDecisionId = reviewCase.getFraudDecisionId();
        this.trafficOrigin = reviewCase.getTrafficOrigin();
        this.source = reviewCase.getReviewStage() == ReviewStage.POST_AUTHORIZATION
                ? "POST_AUTHORIZATION_SIGNAL" : "MANUAL_REVIEW";
        this.labelValue = resolution.getClassification() == ReviewClassification.CONFIRMED_LEGITIMATE
                ? FraudLabelValue.LEGITIMATE : FraudLabelValue.FRAUD;
        this.fraudType = resolution.getFraudType();
        this.taxonomyVersion = ACTIVE_TAXONOMY_VERSION;
        this.evidenceOccurredAt = evidenceOccurredAt;
        this.availableAt = availableAt;
        this.sourceReviewResolutionId = resolution.getId();
        this.sourcePostAuthorizationSignalId = reviewCase.getSourcePostAuthorizationSignalId();
        this.actorOperatorId = actorOperatorId;
        this.reasonCode = resolution.getReasonCode();
        this.createdAt = availableAt;
    }

    public FraudLabel(Chargeback chargeback) {
        this.id = UUID.randomUUID();
        this.merchantId = chargeback.getMerchantId();
        this.paymentIntentId = chargeback.getPaymentIntentId();
        this.paymentAttemptId = chargeback.getPaymentAttemptId();
        this.fraudDecisionId = chargeback.getFraudDecisionId();
        this.trafficOrigin = chargeback.getTrafficOrigin();
        this.source = "CHARGEBACK";
        this.labelValue = FraudLabelValue.FRAUD;
        this.fraudType = chargeback.getReasonCode().fraudType();
        this.taxonomyVersion = ACTIVE_TAXONOMY_VERSION;
        this.evidenceOccurredAt = chargeback.getEvidenceOccurredAt();
        this.availableAt = chargeback.getAvailableAt();
        this.sourceChargebackId = chargeback.getId();
        this.reasonCode = ReviewResolutionReasonCode.CHARGEBACK_FRAUD_CONFIRMED;
        this.createdAt = chargeback.getAvailableAt();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public FraudType getFraudType() { return fraudType; }
    public String getTaxonomyVersion() { return taxonomyVersion; }
    public FraudLabelValue getLabelValue() { return labelValue; }
    public Instant getEvidenceOccurredAt() { return evidenceOccurredAt; }
    public Instant getAvailableAt() { return availableAt; }
    public ReviewResolutionReasonCode getReasonCode() { return reasonCode; }
    public TrafficOrigin getTrafficOrigin() { return trafficOrigin; }
    public UUID getSourceReviewResolutionId() { return sourceReviewResolutionId; }
    public UUID getSourceChargebackId() { return sourceChargebackId; }
    public UUID getSourcePostAuthorizationSignalId() { return sourcePostAuthorizationSignalId; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
