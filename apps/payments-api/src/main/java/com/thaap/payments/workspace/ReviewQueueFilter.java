package com.thaap.payments.workspace;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ReviewQueueFilter(
        Set<ManualReviewCaseStatus> statuses,
        Set<ReviewStage> reviewStages,
        Set<FraudDecisionAction> decisionActions,
        Set<FraudDecisionReasonCode> reasonCodes,
        Set<TrafficOrigin> trafficOrigins,
        Set<UUID> assignedOperatorIds,
        Set<ReviewSlaState> slaStates,
        Instant createdFrom,
        Instant createdTo) {
}
