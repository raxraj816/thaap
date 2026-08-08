package com.thaap.payments.review;

import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudDecisionReasonCodeListConverter;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.postauth.PostAuthorizationSignal;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
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
        @UniqueConstraint(name = "uk_manual_reviews_attempt_stage",
                columnNames = {"merchant_id", "payment_attempt_id", "review_stage"}),
        @UniqueConstraint(name = "uk_manual_reviews_signal",
                columnNames = {"merchant_id", "source_post_authorization_signal_id"})
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
    @Enumerated(EnumType.STRING) @Column(name = "review_stage", nullable = false, length = 32)
    private ReviewStage reviewStage;
    @Column(name = "source_post_authorization_signal_id") private UUID sourcePostAuthorizationSignalId;
    @Column(name = "assigned_operator_id") private UUID assignedOperatorId;
    @Column(name = "assignment_version", nullable = false) private long assignmentVersion;
    @Enumerated(EnumType.STRING) @Column(name = "cancellation_reason", length = 32)
    private ManualReviewCancellationReason cancellationReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "canceled_at") private Instant canceledAt;
    @Column(name = "resolved_at") private Instant resolvedAt;

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
        this.reviewStage = ReviewStage.PRE_AUTHORIZATION;
        this.assignmentVersion = 0;
        this.createdAt = DatabaseInstant.now();
        this.updatedAt = this.createdAt;
    }

    public ManualReviewCase(PostAuthorizationSignal signal, FraudDecision decision) {
        this.id = UUID.randomUUID();
        this.merchantId = signal.getMerchantId();
        this.paymentIntentId = signal.getPaymentIntentId();
        this.paymentAttemptId = signal.getPaymentAttemptId();
        this.fraudDecisionId = signal.getFraudDecisionId();
        this.decisionAction = decision.getAction();
        this.status = ManualReviewCaseStatus.OPEN;
        this.reasonCodes = List.copyOf(decision.getReasonCodes());
        this.trafficOrigin = signal.getTrafficOrigin();
        this.reviewStage = ReviewStage.POST_AUTHORIZATION;
        this.sourcePostAuthorizationSignalId = signal.getId();
        this.assignmentVersion = 0;
        this.createdAt = signal.getAvailableAt();
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
    public ReviewStage getReviewStage() { return reviewStage; }
    public UUID getSourcePostAuthorizationSignalId() { return sourcePostAuthorizationSignalId; }
    public UUID getAssignedOperatorId() { return assignedOperatorId; }
    public long getAssignmentVersion() { return assignmentVersion; }
    public ManualReviewCancellationReason getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCanceledAt() { return canceledAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    public ReviewAssignmentTransition assign(UUID operatorId, long expectedVersion, Instant now) {
        requireExpectedVersion(expectedVersion);
        if (status != ManualReviewCaseStatus.OPEN && status != ManualReviewCaseStatus.ASSIGNED) {
            conflict("assigned");
        }
        if (operatorId.equals(assignedOperatorId)) {
            return null;
        }
        UUID previousOperatorId = assignedOperatorId;
        this.assignedOperatorId = operatorId;
        this.status = ManualReviewCaseStatus.ASSIGNED;
        this.assignmentVersion++;
        this.updatedAt = now;
        ReviewAssignmentEventType eventType = previousOperatorId == null
                ? ReviewAssignmentEventType.ASSIGNED : ReviewAssignmentEventType.REASSIGNED;
        return new ReviewAssignmentTransition(eventType, previousOperatorId, operatorId, assignmentVersion);
    }

    public ReviewAssignmentTransition release(long expectedVersion, Instant now) {
        requireExpectedVersion(expectedVersion);
        if (status != ManualReviewCaseStatus.ASSIGNED || assignedOperatorId == null) {
            conflict("released");
        }
        UUID previousOperatorId = assignedOperatorId;
        this.assignedOperatorId = null;
        this.status = ManualReviewCaseStatus.OPEN;
        this.assignmentVersion++;
        this.updatedAt = now;
        return new ReviewAssignmentTransition(ReviewAssignmentEventType.RELEASED,
                previousOperatorId, null, assignmentVersion);
    }

    public void resolve(Instant now) {
        if (status != ManualReviewCaseStatus.OPEN && status != ManualReviewCaseStatus.ASSIGNED) {
            conflict("resolved");
        }
        this.assignedOperatorId = null;
        this.status = ManualReviewCaseStatus.RESOLVED;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    public void cancelByMerchant() {
        if (status != ManualReviewCaseStatus.OPEN && status != ManualReviewCaseStatus.ASSIGNED) {
            conflict("canceled");
        }
        Instant now = DatabaseInstant.now();
        this.assignedOperatorId = null;
        this.status = ManualReviewCaseStatus.CANCELED;
        this.cancellationReason = ManualReviewCancellationReason.MERCHANT_CANCELED;
        this.canceledAt = now;
        this.updatedAt = now;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (assignmentVersion != expectedVersion) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "ManualReviewCase assignment version is stale");
        }
    }

    private void conflict(String action) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "ManualReviewCase cannot be " + action + " from status " + status);
    }

    public record ReviewAssignmentTransition(ReviewAssignmentEventType eventType,
            UUID fromOperatorId, UUID toOperatorId, long assignmentVersion) {
    }
}
