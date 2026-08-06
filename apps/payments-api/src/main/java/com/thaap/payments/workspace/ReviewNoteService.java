package com.thaap.payments.workspace;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewNoteService {

    private static final Duration RETENTION = Duration.ofDays(90);
    private static final int RETENTION_BATCH_LIMIT = 100;

    private final RiskOperatorRepository operatorRepository;
    private final ManualReviewCaseRepository reviewCaseRepository;
    private final ReviewNoteRepository noteRepository;
    private final ReviewNoteRedactionEventRepository redactionEventRepository;
    private final ReviewNoteContentValidator contentValidator;
    private final ReviewNoteCipher cipher;
    private final AuditLogService auditLogService;
    private final AnalystWorkspaceMetrics metrics;
    private final Clock clock;

    public ReviewNoteService(RiskOperatorRepository operatorRepository,
            ManualReviewCaseRepository reviewCaseRepository, ReviewNoteRepository noteRepository,
            ReviewNoteRedactionEventRepository redactionEventRepository,
            ReviewNoteContentValidator contentValidator, ReviewNoteCipher cipher,
            AuditLogService auditLogService, AnalystWorkspaceMetrics metrics, Clock clock) {
        this.operatorRepository = operatorRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.noteRepository = noteRepository;
        this.redactionEventRepository = redactionEventRepository;
        this.contentValidator = contentValidator;
        this.cipher = cipher;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public ReviewNoteView create(RiskOperatorContext context, UUID reviewCaseId,
            long expectedAssignmentVersion, String content, UUID supersedesNoteId,
            String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        ManualReviewCase reviewCase = lockedCase(reviewCaseId, context.merchantId());
        authorizeCaseAccess(actor, reviewCase, expectedAssignmentVersion);
        ReviewNote superseded = null;
        if (supersedesNoteId != null) {
            superseded = noteRepository.findByIdAndMerchantIdForUpdate(
                            supersedesNoteId, context.merchantId())
                    .filter(note -> note.getManualReviewCaseId().equals(reviewCaseId))
                    .filter(note -> note.getLifecycleStatus() == ReviewNoteLifecycleStatus.ACTIVE)
                    .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.SUPERSEDED_REVIEW_NOTE_NOT_FOUND, "Superseded review note was not found"));
        }
        ReviewNoteContentValidator.ValidatedNote validated = contentValidator.validate(content);
        Instant createdAt = now();
        UUID noteId = UUID.randomUUID();
        ReviewNoteCipher.EncryptedNote encrypted = cipher.encrypt(validated.normalizedContent(),
                context.merchantId(), reviewCaseId, noteId, context.operatorId(), createdAt);
        ReviewNote note = noteRepository.saveAndFlush(new ReviewNote(noteId, context.merchantId(),
                reviewCaseId, context.operatorId(), reviewCase.getAssignmentVersion(),
                superseded == null ? null : superseded.getId(), encrypted,
                validated.codePoints(), createdAt, createdAt.plus(RETENTION)));
        auditLogService.recordRiskOperatorAction(context, requestId,
                superseded == null ? "review_note.created" : "review_note.corrected",
                "review_note", note.getId(), "Risk operator recorded protected review context",
                null, safeState(note));
        metrics.afterCompletion(superseded == null ? "NOTE_CREATE" : "NOTE_CORRECT", "SUCCEEDED");
        return view(note, validated.normalizedContent());
    }

    @Transactional
    public void authorizeCaseForReplay(RiskOperatorContext context, UUID reviewCaseId,
            long expectedAssignmentVersion) {
        RiskOperator actor = liveActorForUpdate(context);
        ManualReviewCase reviewCase = lockedCase(reviewCaseId, context.merchantId());
        authorizeCaseAccess(actor, reviewCase, expectedAssignmentVersion);
    }

    @Transactional
    public void authorizeRedactionForReplay(RiskOperatorContext context, UUID reviewCaseId,
            UUID noteId) {
        RiskOperator actor = liveActorForUpdate(context);
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.REVIEW_NOTE_REDACTION_MANAGER_REQUIRED, "Only a risk manager can redact review notes");
        }
        lockedCase(reviewCaseId, context.merchantId());
        noteRepository.findByIdAndMerchantIdForUpdate(noteId, context.merchantId())
                .filter(note -> note.getManualReviewCaseId().equals(reviewCaseId))
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.REVIEW_NOTE_NOT_FOUND, "Review note was not found"));
    }

    @Transactional
    public List<ReviewNoteView> list(RiskOperatorContext context, UUID reviewCaseId,
            long expectedAssignmentVersion, String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        ManualReviewCase reviewCase = lockedCase(reviewCaseId, context.merchantId());
        authorizeCaseAccess(actor, reviewCase, expectedAssignmentVersion);
        List<UUID> noteIds = noteRepository.findCaseNotes(context.merchantId(), reviewCaseId)
                .stream().map(ReviewNote::getId).toList();
        List<ReviewNote> notes = noteIds.stream()
                .map(noteId -> noteRepository.findByIdAndMerchantIdForUpdate(
                        noteId, context.merchantId()).orElseThrow())
                .toList();
        List<ReviewNoteView> views = notes.stream()
                .map(note -> view(note, decrypt(note)))
                .toList();
        long contentReturnedCount = notes.stream().filter(note -> note.encrypted() != null).count();
        auditLogService.recordRiskOperatorAction(context, requestId, "review_note.accessed",
                "manual_review_case", reviewCaseId,
                "Risk operator accessed protected review context", null,
                Map.of("noteCount", notes.size(), "contentReturnedCount", contentReturnedCount));
        return views;
    }

    @Transactional
    public ReviewNoteView redact(RiskOperatorContext context, UUID reviewCaseId,
            UUID noteId, String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.REVIEW_NOTE_REDACTION_MANAGER_REQUIRED, "Only a risk manager can redact review notes");
        }
        lockedCase(reviewCaseId, context.merchantId());
        ReviewNote note = noteRepository.findByIdAndMerchantIdForUpdate(noteId, context.merchantId())
                .filter(candidate -> candidate.getManualReviewCaseId().equals(reviewCaseId))
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.REVIEW_NOTE_NOT_FOUND, "Review note was not found"));
        Instant now = now();
        if (note.terminate(ReviewNoteRedactionReason.MANAGER_REDACTION, context.operatorId(), now)) {
            noteRepository.saveAndFlush(note);
            redactionEventRepository.saveAndFlush(new ReviewNoteRedactionEvent(note,
                    ReviewNoteRedactionReason.MANAGER_REDACTION, context.operatorId(), now));
            auditLogService.recordRiskOperatorAction(context, requestId, "review_note.redacted",
                    "review_note", note.getId(), "Risk manager redacted protected review context",
                    null, safeState(note));
            metrics.afterCompletion("NOTE_REDACT", "SUCCEEDED");
        }
        return view(note, null);
    }

    @Transactional
    public int expireDueNotes() {
        Instant now = now();
        List<ReviewNote> due = noteRepository.findDueForUpdate(now, RETENTION_BATCH_LIMIT);
        int expired = 0;
        for (ReviewNote note : due) {
            if (note.terminate(ReviewNoteRedactionReason.RETENTION_EXPIRED, null, now)) {
                noteRepository.save(note);
                redactionEventRepository.save(new ReviewNoteRedactionEvent(note,
                        ReviewNoteRedactionReason.RETENTION_EXPIRED, null, now));
                expired++;
            }
        }
        if (expired > 0) {
            noteRepository.flush();
            redactionEventRepository.flush();
        }
        metrics.afterCompletion("NOTE_RETENTION", expired == 0 ? "NO_DUE_NOTES" : "SUCCEEDED");
        return expired;
    }

    private String decrypt(ReviewNote note) {
        ReviewNoteCipher.EncryptedNote encrypted = note.encrypted();
        if (encrypted == null) {
            return null;
        }
        return cipher.decrypt(encrypted, note.getMerchantId(), note.getManualReviewCaseId(),
                note.getId(), note.getAuthorOperatorId(), note.getCreatedAt());
    }

    private void authorizeCaseAccess(RiskOperator actor, ManualReviewCase reviewCase,
            long expectedAssignmentVersion) {
        if (actor.getRole() == RiskOperatorRole.VIEWER) {
            throw new PublicApiException(PublicApiErrorDescriptor.PROTECTED_REVIEW_NOTE_ACCESS_FORBIDDEN, "Viewer role cannot access protected review notes");
        }
        if (reviewCase.getAssignmentVersion() != expectedAssignmentVersion) {
            throw new PublicApiException(PublicApiErrorDescriptor.REVIEW_ASSIGNMENT_VERSION_STALE, "ManualReviewCase assignment version is stale");
        }
        if (actor.getRole() == RiskOperatorRole.ANALYST
                && (reviewCase.getStatus() != ManualReviewCaseStatus.ASSIGNED
                    || !actor.getId().equals(reviewCase.getAssignedOperatorId()))) {
            throw new PublicApiException(PublicApiErrorDescriptor.REVIEW_ANALYST_NOT_ASSIGNED, "Analyst must be assigned to the review case");
        }
    }

    private RiskOperator liveActorForUpdate(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive)
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.REVIEW_CASE_NOT_FOUND, "Review case was not found"));
        if (actor.getRole() != context.role()) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_OPERATOR_STALE, "Risk operator credential is stale");
        }
        return actor;
    }

    private ManualReviewCase lockedCase(UUID reviewCaseId, UUID merchantId) {
        return reviewCaseRepository.findByIdAndMerchantIdForUpdate(reviewCaseId, merchantId)
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.REVIEW_CASE_NOT_FOUND, "Review case was not found"));
    }

    private Map<String, Object> safeState(ReviewNote note) {
        return Map.of("lifecycle", note.getLifecycleStatus().name(),
                "redactionReason", note.getRedactionReason() == null
                        ? "NONE" : note.getRedactionReason().name(),
                "keyVersionPresent", note.encrypted() != null,
                "supersedesNote", note.getSupersedesNoteId() != null,
                "retentionExpiresAt", note.getRetentionExpiresAt().toString());
    }

    private ReviewNoteView view(ReviewNote note, String content) {
        return new ReviewNoteView(note.getId(), note.getManualReviewCaseId(),
                note.getAuthorOperatorId(), note.getAuthorAssignmentVersion(),
                note.getSupersedesNoteId(), note.getLifecycleStatus(), note.getCreatedAt(),
                note.getRetentionExpiresAt(), note.getRedactedAt(), note.getRedactionReason(), content);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record ReviewNoteView(UUID id, UUID reviewCaseId, UUID authorOperatorId,
            long authorAssignmentVersion, UUID supersedesNoteId,
            ReviewNoteLifecycleStatus lifecycleStatus, Instant createdAt,
            Instant retentionExpiresAt, Instant redactedAt,
            ReviewNoteRedactionReason redactionReason, String content) {
    }
}
