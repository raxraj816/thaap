package com.thaap.payments.bulk;

import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewBulkItemExecutor {

    private final ReviewBulkDomainMutation mutation;
    private final ReviewBulkActionStore store;
    private final ManualReviewCaseRepository reviewCaseRepository;
    private final DecisionIntelligenceMetrics metrics;

    public ReviewBulkItemExecutor(ReviewBulkDomainMutation mutation,
            ReviewBulkActionStore store, ManualReviewCaseRepository reviewCaseRepository,
            DecisionIntelligenceMetrics metrics) {
        this.mutation = mutation;
        this.store = store;
        this.reviewCaseRepository = reviewCaseRepository;
        this.metrics = metrics;
    }

    public void execute(ReviewBulkActionStore.ClaimedItem item) {
        try {
            mutation.apply(item);
            metrics.bulkItem("SUCCEEDED");
        } catch (ResponseStatusException exception) {
            Classification classification = classify(exception.getStatusCode());
            Long observedVersion = observedVersion(item);
            store.completeFailure(item, classification.status(), classification.code(),
                    observedVersion);
            metrics.bulkItem(classification.status());
        } catch (RuntimeException exception) {
            store.completeFailure(item, "FAILED", "mutation_execution_failed",
                    observedVersion(item));
            metrics.bulkItem("FAILED");
        }
    }

    private Long observedVersion(ReviewBulkActionStore.ClaimedItem item) {
        return reviewCaseRepository.findByIdAndMerchantId(
                item.reviewCaseId(), item.merchantId())
                .map(ManualReviewCase::getAssignmentVersion).orElse(null);
    }

    private Classification classify(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> new Classification("NOT_FOUND", "review_or_operator_not_found");
            case 401, 403 -> new Classification("DENIED", "actor_or_target_denied");
            case 409, 412 -> new Classification("STALE", "assignment_version_stale");
            case 400 -> new Classification("NOT_ELIGIBLE", "review_not_eligible");
            default -> throw new IllegalStateException("Unexpected bulk review mutation failure");
        };
    }

    private record Classification(String status, String code) {
    }
}
