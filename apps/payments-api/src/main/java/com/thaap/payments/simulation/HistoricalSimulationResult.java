package com.thaap.payments.simulation;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.UUID;

public record HistoricalSimulationResult(
        UUID paymentAttemptId,
        UUID fraudDecisionId,
        Instant decisionCreatedAt,
        TrafficOrigin trafficOrigin,
        FraudDecisionAction originalAction,
        FraudDecisionAction candidateAction,
        boolean eligible,
        RiskSimulationExclusionReason exclusionReason,
        RiskSimulationLabelOutcome labelOutcome,
        boolean protectedSuppressionAttempted,
        long authorizationAmount,
        long captureAmount,
        long refundAmount) { }
