package com.thaap.payments.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_evidence_links")
public class IncidentEvidenceLink {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Enumerated(EnumType.STRING) @Column(name = "evidence_type", nullable = false, length = 32)
    private IncidentEvidenceType evidenceType;
    @Column(name = "observed_at", nullable = false) private Instant observedAt;
    @Column(name = "linked_at", nullable = false) private Instant linkedAt;

    protected IncidentEvidenceLink() {}

    public IncidentEvidenceLink(UUID merchantId, UUID incidentId, UUID paymentAttemptId,
            UUID fraudDecisionId, IncidentEvidenceType evidenceType, Instant observedAt, Instant linkedAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.incidentId = incidentId;
        this.paymentAttemptId = paymentAttemptId;
        this.fraudDecisionId = fraudDecisionId;
        this.evidenceType = evidenceType;
        this.observedAt = observedAt;
        this.linkedAt = linkedAt;
    }
}
