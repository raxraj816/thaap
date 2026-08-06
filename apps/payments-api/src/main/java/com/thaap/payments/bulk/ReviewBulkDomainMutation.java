package com.thaap.payments.bulk;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ReviewOperationsService;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewBulkDomainMutation {

    private final RiskOperatorRepository operatorRepository;
    private final ReviewOperationsService reviewOperationsService;
    private final ReviewBulkActionStore store;

    public ReviewBulkDomainMutation(RiskOperatorRepository operatorRepository,
            ReviewOperationsService reviewOperationsService, ReviewBulkActionStore store) {
        this.operatorRepository = operatorRepository;
        this.reviewOperationsService = reviewOperationsService;
        this.store = store;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(ReviewBulkActionStore.ClaimedItem item) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantId(
                item.initiatingOperatorId(), item.merchantId())
                .filter(RiskOperator::isActive)
                .orElseThrow(() -> new PublicApiException(
                        PublicApiErrorDescriptor.BULK_ACTOR_UNAVAILABLE));
        RiskOperatorContext context = new RiskOperatorContext(item.merchantId(), actor.getId(),
                actor.getOperatorCode(), actor.getRole(), "bulk-worker");
        String requestId = "review-bulk:" + item.requestId() + ":" + item.ordinal();
        ManualReviewCase review;
        if (item.action() == ReviewBulkActionCode.RELEASE) {
            review = reviewOperationsService.release(context, item.reviewCaseId(),
                    item.expectedAssignmentVersion(), requestId);
        } else {
            UUID target = item.action() == ReviewBulkActionCode.ASSIGN_TO_SELF
                    ? actor.getId() : item.targetOperatorId();
            review = reviewOperationsService.assign(context, item.reviewCaseId(), target,
                    item.expectedAssignmentVersion(), requestId);
        }
        store.completeSuccessInCurrentTransaction(item, review.getAssignmentVersion());
    }
}
