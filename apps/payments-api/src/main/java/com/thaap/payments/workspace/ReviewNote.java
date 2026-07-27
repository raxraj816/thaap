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
@Table(name = "review_notes")
public class ReviewNote {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "manual_review_case_id", nullable = false) private UUID manualReviewCaseId;
    @Column(name = "author_operator_id", nullable = false) private UUID authorOperatorId;
    @Column(name = "author_assignment_version", nullable = false) private long authorAssignmentVersion;
    @Column(name = "supersedes_note_id") private UUID supersedesNoteId;
    @Column(columnDefinition = "TEXT") private String ciphertext;
    @Column(length = 24) private String nonce;
    @Column(name = "key_version", length = 32) private String keyVersion;
    @Column(name = "plaintext_code_points", nullable = false) private int plaintextCodePoints;
    @Enumerated(EnumType.STRING) @Column(name = "lifecycle_status", nullable = false, length = 16)
    private ReviewNoteLifecycleStatus lifecycleStatus;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "retention_expires_at", nullable = false) private Instant retentionExpiresAt;
    @Column(name = "redacted_at") private Instant redactedAt;
    @Column(name = "redacted_by_operator_id") private UUID redactedByOperatorId;
    @Enumerated(EnumType.STRING) @Column(name = "redaction_reason", length = 32)
    private ReviewNoteRedactionReason redactionReason;

    protected ReviewNote() {
    }

    public ReviewNote(UUID id, UUID merchantId, UUID manualReviewCaseId, UUID authorOperatorId,
            long authorAssignmentVersion, UUID supersedesNoteId,
            ReviewNoteCipher.EncryptedNote encrypted, int plaintextCodePoints,
            Instant createdAt, Instant retentionExpiresAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.manualReviewCaseId = manualReviewCaseId;
        this.authorOperatorId = authorOperatorId;
        this.authorAssignmentVersion = authorAssignmentVersion;
        this.supersedesNoteId = supersedesNoteId;
        this.ciphertext = encrypted.ciphertext();
        this.nonce = encrypted.nonce();
        this.keyVersion = encrypted.keyVersion();
        this.plaintextCodePoints = plaintextCodePoints;
        this.lifecycleStatus = ReviewNoteLifecycleStatus.ACTIVE;
        this.createdAt = createdAt;
        this.retentionExpiresAt = retentionExpiresAt;
    }

    public boolean terminate(ReviewNoteRedactionReason reason, UUID actorOperatorId, Instant now) {
        if (lifecycleStatus != ReviewNoteLifecycleStatus.ACTIVE) {
            return false;
        }
        this.ciphertext = null;
        this.nonce = null;
        this.keyVersion = null;
        this.lifecycleStatus = reason == ReviewNoteRedactionReason.MANAGER_REDACTION
                ? ReviewNoteLifecycleStatus.REDACTED : ReviewNoteLifecycleStatus.EXPIRED;
        this.redactedAt = now;
        this.redactedByOperatorId = actorOperatorId;
        this.redactionReason = reason;
        return true;
    }

    public ReviewNoteCipher.EncryptedNote encrypted() {
        if (lifecycleStatus != ReviewNoteLifecycleStatus.ACTIVE
                || ciphertext == null || nonce == null || keyVersion == null) {
            return null;
        }
        return new ReviewNoteCipher.EncryptedNote(ciphertext, nonce, keyVersion);
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getManualReviewCaseId() { return manualReviewCaseId; }
    public UUID getAuthorOperatorId() { return authorOperatorId; }
    public long getAuthorAssignmentVersion() { return authorAssignmentVersion; }
    public UUID getSupersedesNoteId() { return supersedesNoteId; }
    public int getPlaintextCodePoints() { return plaintextCodePoints; }
    public ReviewNoteLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRetentionExpiresAt() { return retentionExpiresAt; }
    public Instant getRedactedAt() { return redactedAt; }
    public UUID getRedactedByOperatorId() { return redactedByOperatorId; }
    public ReviewNoteRedactionReason getRedactionReason() { return redactionReason; }
}
