package com.thaap.payments.postauth;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/post_authorization_signals")
public class PostAuthorizationSignalController {
    private final PostAuthorizationSignalRepository repository;
    private final PostAuthorizationSignalService service;
    private final IdempotencyService idempotencyService;
    private final ManualReviewCaseRepository reviewCaseRepository;

    public PostAuthorizationSignalController(PostAuthorizationSignalRepository repository,
            PostAuthorizationSignalService service, IdempotencyService idempotencyService,
            ManualReviewCaseRepository reviewCaseRepository) {
        this.repository = repository;
        this.service = service;
        this.idempotencyService = idempotencyService;
        this.reviewCaseRepository = reviewCaseRepository;
    }

    @PostMapping
    public ResponseEntity<SignalResponse> create(MerchantContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSignalRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "post_authorization_signals.create",
                request, SignalResponse.class, () -> service.create(context, request, requestId(servletRequest)));
    }

    @GetMapping("/{id}")
    public SignalResponse get(MerchantContext context, @PathVariable UUID id) {
        PostAuthorizationSignal signal = repository.findByIdAndMerchantId(id, context.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "PostAuthorizationSignal was not found"));
        ManualReviewCase reviewCase = signal.getRecommendation() == PostAuthorizationSignalRecommendation.REVIEW
                ? reviewCaseRepository.findByPaymentIntentIdAndMerchantIdAndReviewStage(
                        signal.getPaymentIntentId(), context.merchantId(),
                        ReviewStage.POST_AUTHORIZATION).orElse(null)
                : null;
        return SignalResponse.from(signal, reviewCase);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null || value.toString().isBlank() ? "unknown" : value.toString();
    }

    public record CreateSignalRequest(
            @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String externalEventKey,
            @NotNull UUID paymentIntentId,
            @NotNull PostAuthorizationSignalRecommendation recommendation,
            @NotNull PostAuthorizationSignalReasonCode reasonCode,
            @NotNull Instant evidenceOccurredAt,
            @NotNull Instant receivedAt) {}

    public record SignalResponse(UUID id, UUID paymentIntentId, UUID paymentAttemptId,
            UUID authorizationId, UUID fraudDecisionId,
            PostAuthorizationSignalRecommendation recommendation,
            PostAuthorizationSignalReasonCode reasonCode, String sourceAttestation,
            TrafficOrigin trafficOrigin, Instant evidenceOccurredAt, Instant receivedAt,
            Instant availableAt, UUID reviewCaseId, String actionTaken, Instant createdAt) {
        static SignalResponse from(PostAuthorizationSignal signal, ManualReviewCase reviewCase) {
            String action = reviewCase == null ? "NONE"
                    : reviewCase.getSourcePostAuthorizationSignalId().equals(signal.getId())
                            ? "REVIEW_OPENED" : "REVIEW_ALREADY_OPEN";
            return new SignalResponse(signal.getId(), signal.getPaymentIntentId(),
                    signal.getPaymentAttemptId(), signal.getAuthorizationId(), signal.getFraudDecisionId(),
                    signal.getRecommendation(), signal.getReasonCode(), signal.getSourceAttestation(),
                    signal.getTrafficOrigin(), signal.getEvidenceOccurredAt(), signal.getReceivedAt(),
                    signal.getAvailableAt(), reviewCase == null ? null : reviewCase.getId(),
                    action, signal.getCreatedAt());
        }
    }
}
