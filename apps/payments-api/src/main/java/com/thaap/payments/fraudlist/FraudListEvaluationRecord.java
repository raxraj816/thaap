package com.thaap.payments.fraudlist;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_list_evaluations")
public class FraudListEvaluationRecord {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Column(name = "fraud_list_id", nullable = false) private UUID fraudListId;
    @Column(name = "fraud_list_version_id", nullable = false) private UUID fraudListVersionId;
    @Column(name = "matched_entry_id") private UUID matchedEntryId;
    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 32) private FraudListIdentityType identityType;
    @Column(nullable = false) private boolean matched;
    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_action", length = 16) private FraudDecisionAction candidateAction;
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 64) private FraudListReasonCode reasonCode;
    @Column(name = "evaluated_at", nullable = false) private Instant evaluatedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32) private TrafficOrigin trafficOrigin;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected FraudListEvaluationRecord() {
    }

    public FraudListEvaluationRecord(UUID merchantId, UUID paymentAttemptId, UUID fraudDecisionId,
            FraudListEvaluation evaluation, TrafficOrigin trafficOrigin, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentAttemptId = paymentAttemptId;
        this.fraudDecisionId = fraudDecisionId;
        this.fraudListId = evaluation.listId();
        this.fraudListVersionId = evaluation.versionId();
        this.matchedEntryId = evaluation.matchedEntryId();
        this.identityType = evaluation.identityType();
        this.matched = evaluation.matched();
        this.candidateAction = evaluation.candidateAction();
        this.reasonCode = evaluation.reasonCode();
        this.evaluatedAt = evaluation.evaluatedAt();
        this.trafficOrigin = trafficOrigin;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public UUID getMatchedEntryId() { return matchedEntryId; }
    public boolean isMatched() { return matched; }
}
