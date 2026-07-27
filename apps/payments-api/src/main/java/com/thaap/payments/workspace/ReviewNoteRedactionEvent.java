package com.thaap.payments.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_note_redaction_events")
public class ReviewNoteRedactionEvent {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "review_note_id", nullable = false) private UUID reviewNoteId;
    @Column(name = "manual_review_case_id", nullable = false) private UUID manualReviewCaseId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 32)
    private ReviewNoteRedactionReason eventType;
    @Column(name = "actor_operator_id") private UUID actorOperatorId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ReviewNoteRedactionEvent() {
    }

    public ReviewNoteRedactionEvent(ReviewNote note, ReviewNoteRedactionReason reason,
            UUID actorOperatorId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = note.getMerchantId();
        this.reviewNoteId = note.getId();
        this.manualReviewCaseId = note.getManualReviewCaseId();
        this.eventType = reason;
        this.actorOperatorId = actorOperatorId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getReviewNoteId() { return reviewNoteId; }
    public UUID getManualReviewCaseId() { return manualReviewCaseId; }
    public ReviewNoteRedactionReason getEventType() { return eventType; }
    public UUID getActorOperatorId() { return actorOperatorId; }
    public Instant getCreatedAt() { return createdAt; }
}
