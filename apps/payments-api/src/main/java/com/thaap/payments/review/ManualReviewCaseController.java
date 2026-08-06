package com.thaap.payments.review;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ManualReviewCaseController {

    private final ManualReviewCaseRepository repository;

    public ManualReviewCaseController(ManualReviewCaseRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/fraud/reviews")
    public List<ManualReviewCaseResponse> list(MerchantContext merchantContext,
            @RequestParam(defaultValue = "OPEN") ManualReviewCaseStatus status,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(name = "starting_after", required = false) UUID startingAfter) {
        if (limit < 1 || limit > 100) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAGE_LIMIT_INVALID, "limit must be between 1 and 100");
        }
        Instant cursorCreatedAt = null;
        UUID cursorId = null;
        if (startingAfter != null) {
            ManualReviewCase cursor = repository.findByIdAndMerchantId(startingAfter, merchantContext.merchantId())
                    .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.REVIEW_CASE_CURSOR_NOT_FOUND, "ManualReviewCase cursor was not found"));
            cursorCreatedAt = cursor.getCreatedAt();
            cursorId = cursor.getId();
        }
        List<ManualReviewCase> cases = startingAfter == null
                ? repository.listFirst(merchantContext.merchantId(), status, PageRequest.of(0, limit))
                : repository.listAfter(merchantContext.merchantId(), status, cursorCreatedAt, cursorId,
                        PageRequest.of(0, limit));
        return cases.stream()
                .map(ManualReviewCaseResponse::from).toList();
    }

    @GetMapping("/fraud/reviews/{id}")
    public ManualReviewCaseResponse detail(MerchantContext merchantContext, @PathVariable UUID id) {
        return repository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .map(ManualReviewCaseResponse::from)
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.MANUAL_REVIEW_CASE_NOT_FOUND, "ManualReviewCase was not found"));
    }

    public record ManualReviewCaseResponse(UUID id, UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, ManualReviewCaseStatus status, List<FraudDecisionReasonCode> reasonCodes,
            TrafficOrigin trafficOrigin, ManualReviewCancellationReason cancellationReason,
            Instant createdAt, Instant updatedAt, Instant canceledAt) {
        static ManualReviewCaseResponse from(ManualReviewCase reviewCase) {
            return new ManualReviewCaseResponse(reviewCase.getId(), reviewCase.getMerchantId(),
                    reviewCase.getPaymentIntentId(), reviewCase.getPaymentAttemptId(),
                    reviewCase.getFraudDecisionId(), reviewCase.getStatus(), reviewCase.getReasonCodes(),
                    reviewCase.getTrafficOrigin(), reviewCase.getCancellationReason(), reviewCase.getCreatedAt(),
                    reviewCase.getUpdatedAt(), reviewCase.getCanceledAt());
        }
    }
}
