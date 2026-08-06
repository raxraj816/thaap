package com.thaap.payments.investigation;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.review.FraudLabelValue;
import com.thaap.payments.review.FraudType;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.review.ReviewResolutionOutcome;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.Set;

public record PaymentInvestigationFilter(
        Set<PaymentIntentStatus> lifecycleStatuses,
        Set<FraudDecisionAction> fraudActions,
        Set<FraudRiskLevel> riskLevels,
        Set<FraudDecisionReasonCode> reasonCodes,
        Set<ManualReviewCaseStatus> reviewStatuses,
        Set<ReviewResolutionOutcome> reviewOutcomes,
        Set<FraudLabelValue> labelValues,
        Set<FraudType> fraudTypes,
        Set<TrafficOrigin> trafficOrigins,
        Set<String> currencies,
        Long minimumAmountMinor,
        Long maximumAmountMinor,
        Instant createdFrom,
        Instant createdTo) {
}

