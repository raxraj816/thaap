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
@Table(name = "review_annotations")
public class ReviewAnnotation {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "manual_review_case_id", nullable = false) private UUID manualReviewCaseId;
    @Column(name = "actor_operator_id", nullable = false) private UUID actorOperatorId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) private ReviewAnnotationCode code;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ReviewAnnotation() {
    }

    public ReviewAnnotation(UUID merchantId, UUID reviewCaseId, UUID actorOperatorId,
            ReviewAnnotationCode code, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.manualReviewCaseId = reviewCaseId;
        this.actorOperatorId = actorOperatorId;
        this.code = code;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public ReviewAnnotationCode getCode() { return code; }
    public Instant getCreatedAt() { return createdAt; }
}
