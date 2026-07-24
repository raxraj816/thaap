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
@Table(name = "card_testing_incidents")
public class CardTestingIncident {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private CardTestingIncidentStatus status;
    @Column(name = "open_slot") private Boolean openSlot;
    @Column(name = "trigger_code", nullable = false, length = 64) private String triggerCode;
    @Column(name = "source_attestation", nullable = false, length = 64) private String sourceAttestation;
    @Column(name = "detection_window_started_at", nullable = false) private Instant detectionWindowStartedAt;
    @Column(name = "detection_window_ended_at", nullable = false) private Instant detectionWindowEndedAt;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "decline_count", nullable = false) private int declineCount;
    @Column(name = "distinct_card_count", nullable = false) private int distinctCardCount;
    @Column(name = "distinct_bin_count", nullable = false) private int distinctBinCount;
    @Column(name = "distinct_ip_count", nullable = false) private int distinctIpCount;
    @Column(name = "first_evidence_at", nullable = false) private Instant firstEvidenceAt;
    @Column(name = "last_evidence_at", nullable = false) private Instant lastEvidenceAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected CardTestingIncident() {}

    public CardTestingIncident(UUID merchantId, Instant windowStart, Instant windowEnd,
            IncidentAggregate aggregate, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.status = CardTestingIncidentStatus.OPEN;
        this.openSlot = true;
        this.triggerCode = "PERSISTED_CARD_TESTING_PATTERN";
        this.sourceAttestation = "THAAP_PERSISTED_CONTROL_EVIDENCE";
        this.detectionWindowStartedAt = windowStart;
        this.detectionWindowEndedAt = windowEnd;
        applyAggregate(aggregate, now);
        this.createdAt = now;
    }

    public void applyAggregate(IncidentAggregate aggregate, Instant now) {
        if (status != CardTestingIncidentStatus.OPEN) {
            throw new IllegalStateException("Resolved card-testing incident cannot accept evidence");
        }
        this.attemptCount = Math.max(attemptCount, aggregate.attemptCount());
        this.declineCount = Math.max(declineCount, aggregate.declineCount());
        this.distinctCardCount = Math.max(distinctCardCount, aggregate.distinctCardCount());
        this.distinctBinCount = Math.max(distinctBinCount, aggregate.distinctBinCount());
        this.distinctIpCount = Math.max(distinctIpCount, aggregate.distinctIpCount());
        this.firstEvidenceAt = firstEvidenceAt == null || aggregate.firstEvidenceAt().isBefore(firstEvidenceAt)
                ? aggregate.firstEvidenceAt() : firstEvidenceAt;
        this.lastEvidenceAt = lastEvidenceAt == null || aggregate.lastEvidenceAt().isAfter(lastEvidenceAt)
                ? aggregate.lastEvidenceAt() : lastEvidenceAt;
        this.updatedAt = now;
    }

    public void resolve(Instant now) {
        if (status != CardTestingIncidentStatus.OPEN) {
            return;
        }
        this.status = CardTestingIncidentStatus.RESOLVED;
        this.openSlot = null;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public CardTestingIncidentStatus getStatus() { return status; }
    public String getTriggerCode() { return triggerCode; }
    public String getSourceAttestation() { return sourceAttestation; }
    public Instant getDetectionWindowStartedAt() { return detectionWindowStartedAt; }
    public Instant getDetectionWindowEndedAt() { return detectionWindowEndedAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getDeclineCount() { return declineCount; }
    public int getDistinctCardCount() { return distinctCardCount; }
    public int getDistinctBinCount() { return distinctBinCount; }
    public int getDistinctIpCount() { return distinctIpCount; }
    public Instant getFirstEvidenceAt() { return firstEvidenceAt; }
    public Instant getLastEvidenceAt() { return lastEvidenceAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public record IncidentAggregate(int attemptCount, int declineCount, int distinctCardCount,
            int distinctBinCount, int distinctIpCount, Instant firstEvidenceAt, Instant lastEvidenceAt) {}
}
