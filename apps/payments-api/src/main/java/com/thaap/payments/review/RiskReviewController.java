package com.thaap.payments.review;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authentication.AuthenticationNextAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/risk/reviews")
public class RiskReviewController {

    private final ManualReviewCaseRepository reviewCaseRepository;
    private final ReviewOperationsService operationsService;
    private final IdempotencyService idempotencyService;

    public RiskReviewController(ManualReviewCaseRepository reviewCaseRepository,
            ReviewOperationsService operationsService, IdempotencyService idempotencyService) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.operationsService = operationsService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public List<RiskReviewResponse> list(RiskOperatorContext context,
            @RequestParam(defaultValue = "OPEN") ManualReviewCaseStatus status,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(name = "starting_after", required = false) UUID startingAfter) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        Instant cursorCreatedAt = null;
        UUID cursorId = null;
        if (startingAfter != null) {
            ManualReviewCase cursor = reviewCaseRepository.findByIdAndMerchantId(startingAfter, context.merchantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "ManualReviewCase cursor was not found"));
            cursorCreatedAt = cursor.getCreatedAt();
            cursorId = cursor.getId();
        }
        List<ManualReviewCase> cases = startingAfter == null
                ? reviewCaseRepository.listFirst(context.merchantId(), status, PageRequest.of(0, limit))
                : reviewCaseRepository.listAfter(context.merchantId(), status, cursorCreatedAt, cursorId,
                        PageRequest.of(0, limit));
        return cases.stream().map(RiskReviewResponse::from).toList();
    }

    @GetMapping("/{id}")
    public RiskReviewResponse detail(RiskOperatorContext context, @PathVariable UUID id) {
        return reviewCaseRepository.findByIdAndMerchantId(id, context.merchantId())
                .map(RiskReviewResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ManualReviewCase was not found"));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<RiskReviewResponse> assign(RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AssignReviewRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.reviews.assign:" + id, request, RiskReviewResponse.class,
                () -> ResponseEntity.ok(RiskReviewResponse.from(operationsService.assign(context, id,
                        request.operatorId(), request.expectedAssignmentVersion(), requestId(servletRequest)))));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<RiskReviewResponse> release(RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReleaseReviewRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.reviews.release:" + id, request, RiskReviewResponse.class,
                () -> ResponseEntity.ok(RiskReviewResponse.from(operationsService.release(context, id,
                        request.expectedAssignmentVersion(), requestId(servletRequest)))));
    }

    @PostMapping("/{id}/annotations")
    public ResponseEntity<ReviewAnnotationResponse> annotate(RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAnnotationRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.reviews.annotate:" + id, request, ReviewAnnotationResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(ReviewAnnotationResponse.from(
                        operationsService.annotate(context, id, request.code(), requestId(servletRequest)))));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<ReviewResolutionResponse> resolve(RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResolveReviewRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.reviews.resolve:" + id, request, ReviewResolutionResponse.class,
                () -> ResponseEntity.ok(ReviewResolutionResponse.from(operationsService.resolve(context, id,
                        request.outcome(), request.classification(), request.fraudType(), request.reasonCode(),
                        request.evidenceOccurredAt(), requestId(servletRequest)))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record AssignReviewRequest(UUID operatorId, @Min(0) long expectedAssignmentVersion) {
    }
    public record ReleaseReviewRequest(@Min(0) long expectedAssignmentVersion) {
    }
    public record CreateAnnotationRequest(@NotNull ReviewAnnotationCode code) {
    }
    public record ResolveReviewRequest(@NotNull ReviewResolutionOutcome outcome,
            @NotNull ReviewClassification classification, FraudType fraudType,
            @NotNull ReviewResolutionReasonCode reasonCode, @NotNull Instant evidenceOccurredAt) {
    }
    public record RiskReviewResponse(UUID id, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, ReviewStage reviewStage, ManualReviewCaseStatus status,
            List<FraudDecisionReasonCode> reasonCodes, TrafficOrigin trafficOrigin,
            UUID sourcePostAuthorizationSignalId,
            UUID assignedOperatorId, long assignmentVersion, Instant createdAt, Instant updatedAt,
            Instant canceledAt, Instant resolvedAt) {
        static RiskReviewResponse from(ManualReviewCase reviewCase) {
            return new RiskReviewResponse(reviewCase.getId(), reviewCase.getPaymentIntentId(),
                    reviewCase.getPaymentAttemptId(), reviewCase.getFraudDecisionId(),
                    reviewCase.getReviewStage(), reviewCase.getStatus(), reviewCase.getReasonCodes(),
                    reviewCase.getTrafficOrigin(), reviewCase.getSourcePostAuthorizationSignalId(),
                    reviewCase.getAssignedOperatorId(),
                    reviewCase.getAssignmentVersion(), reviewCase.getCreatedAt(), reviewCase.getUpdatedAt(),
                    reviewCase.getCanceledAt(), reviewCase.getResolvedAt());
        }
    }
    public record ReviewAnnotationResponse(UUID id, ReviewAnnotationCode code, Instant createdAt) {
        static ReviewAnnotationResponse from(ReviewAnnotation annotation) {
            return new ReviewAnnotationResponse(annotation.getId(), annotation.getCode(), annotation.getCreatedAt());
        }
    }
    public record ReviewResolutionResponse(UUID id, UUID reviewCaseId, UUID paymentIntentId,
            UUID paymentAttemptId, UUID fraudDecisionId, ReviewResolutionOutcome outcome,
            ReviewClassification classification, FraudType fraudType, ReviewResolutionReasonCode reasonCode,
            Instant resolvedAt, FraudLabelResponse label, UUID authorizationId,
            AuthorizationStatus processorOutcome, PaymentIntentStatus paymentIntentStatus,
            PaymentAttemptStatus paymentAttemptStatus, AuthenticationNextAction nextAction,
            UUID refundId, com.thaap.payments.payment.RefundReasonCode refundReasonCode,
            String refundActionResult,
            com.thaap.payments.payment.RefundReasonCode refundActionReasonCode) {
        static ReviewResolutionResponse from(ReviewOperationsService.ResolutionResult result) {
            ReviewResolution resolution = result.resolution();
            return new ReviewResolutionResponse(resolution.getId(), resolution.getManualReviewCaseId(),
                    resolution.getPaymentIntentId(), resolution.getPaymentAttemptId(),
                    resolution.getFraudDecisionId(), resolution.getOutcome(), resolution.getClassification(),
                    resolution.getFraudType(), resolution.getReasonCode(), resolution.getResolvedAt(),
                    result.label() == null ? null : FraudLabelResponse.from(result.label()),
                    result.authorization() == null ? null : result.authorization().getId(),
                    result.authorization() == null ? null : result.authorization().getStatus(),
                    result.paymentIntentStatus(), result.paymentAttemptStatus(), result.nextAction(),
                    result.refund() == null ? null : result.refund().getId(),
                    result.refund() == null ? null : result.refund().getReasonCode(),
                    result.refundAction() == null ? null : result.refundAction().getActionResult(),
                    result.refundAction() == null ? null : result.refundAction().getReasonCode());
        }
    }
    public record FraudLabelResponse(UUID id, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, FraudLabelValue labelValue, FraudType fraudType,
            String taxonomyVersion,
            Instant evidenceOccurredAt, Instant availableAt) {
        static FraudLabelResponse from(FraudLabel label) {
            return new FraudLabelResponse(label.getId(), label.getPaymentIntentId(), label.getPaymentAttemptId(),
                    label.getFraudDecisionId(), label.getLabelValue(), label.getFraudType(),
                    label.getTaxonomyVersion(), label.getEvidenceOccurredAt(),
                    label.getAvailableAt());
        }
    }
}
