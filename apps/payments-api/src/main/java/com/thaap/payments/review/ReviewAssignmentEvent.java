package com.thaap.payments.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_assignment_events")
public class ReviewAssignmentEvent {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "manual_review_case_id", nullable = false) private UUID manualReviewCaseId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 16)
    private ReviewAssignmentEventType eventType;
    @Column(name = "from_operator_id") private UUID fromOperatorId;
    @Column(name = "to_operator_id") private UUID toOperatorId;
    @Column(name = "actor_operator_id", nullable = false) private UUID actorOperatorId;
    @Column(name = "assignment_version", nullable = false) private long assignmentVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ReviewAssignmentEvent() {
    }

    public ReviewAssignmentEvent(UUID merchantId, UUID manualReviewCaseId,
            ManualReviewCase.ReviewAssignmentTransition transition, UUID actorOperatorId, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.manualReviewCaseId = manualReviewCaseId;
        this.eventType = transition.eventType();
        this.fromOperatorId = transition.fromOperatorId();
        this.toOperatorId = transition.toOperatorId();
        this.actorOperatorId = actorOperatorId;
        this.assignmentVersion = transition.assignmentVersion();
        this.createdAt = now;
    }

    public UUID getId() { return id; }
}
