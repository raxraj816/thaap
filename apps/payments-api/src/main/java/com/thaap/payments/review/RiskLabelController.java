package com.thaap.payments.review;

import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/risk/labels")
public class RiskLabelController {

    private final FraudLabelRepository repository;

    public RiskLabelController(FraudLabelRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<FraudLabelReadResponse> list(RiskOperatorContext context,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(name = "starting_after", required = false) UUID startingAfter) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        List<FraudLabel> labels;
        if (startingAfter == null) {
            labels = repository.findByMerchantIdOrderByCreatedAtAscIdAsc(
                    context.merchantId(), PageRequest.of(0, limit));
        } else {
            FraudLabel cursor = repository.findByIdAndMerchantId(startingAfter, context.merchantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "FraudLabel cursor was not found"));
            labels = repository.listAfter(context.merchantId(), cursor.getCreatedAt(), cursor.getId(),
                    PageRequest.of(0, limit));
        }
        return labels.stream().map(FraudLabelReadResponse::from).toList();
    }

    @GetMapping("/{id}")
    public FraudLabelReadResponse detail(RiskOperatorContext context, @PathVariable UUID id) {
        return repository.findByIdAndMerchantId(id, context.merchantId())
                .map(FraudLabelReadResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "FraudLabel was not found"));
    }

    public record FraudLabelReadResponse(UUID id, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, TrafficOrigin trafficOrigin, FraudLabelValue labelValue, FraudType fraudType,
            Instant evidenceOccurredAt, Instant availableAt, String source, UUID sourceReviewResolutionId,
            UUID sourceChargebackId,
            ReviewResolutionReasonCode reasonCode, Instant createdAt) {
        static FraudLabelReadResponse from(FraudLabel label) {
            return new FraudLabelReadResponse(label.getId(), label.getPaymentIntentId(),
                    label.getPaymentAttemptId(), label.getFraudDecisionId(), label.getTrafficOrigin(),
                    label.getLabelValue(), label.getFraudType(), label.getEvidenceOccurredAt(), label.getAvailableAt(),
                    label.getSource(), label.getSourceReviewResolutionId(), label.getSourceChargebackId(),
                    label.getReasonCode(), label.getCreatedAt());
        }
    }
}
