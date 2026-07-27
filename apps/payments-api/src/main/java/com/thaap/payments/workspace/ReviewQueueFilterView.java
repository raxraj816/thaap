package com.thaap.payments.workspace;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewQueueFilterView(
        List<ManualReviewCaseStatus> statuses,
        List<ReviewStage> reviewStages,
        List<FraudDecisionAction> decisionActions,
        List<FraudDecisionReasonCode> reasonCodes,
        List<TrafficOrigin> trafficOrigins,
        List<UUID> assignedOperatorIds,
        List<ReviewSlaState> slaStates,
        Instant createdFrom,
        Instant createdTo) {

    public static ReviewQueueFilterView from(ReviewQueueFilter filter) {
        return new ReviewQueueFilterView(sorted(filter.statuses()),
                sorted(filter.reviewStages()), sorted(filter.decisionActions()),
                sorted(filter.reasonCodes()), sorted(filter.trafficOrigins()),
                filter.assignedOperatorIds().stream().sorted().toList(),
                sorted(filter.slaStates()), filter.createdFrom(), filter.createdTo());
    }

    private static <E extends Enum<E>> List<E> sorted(java.util.Set<E> values) {
        return values.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList();
    }
}
