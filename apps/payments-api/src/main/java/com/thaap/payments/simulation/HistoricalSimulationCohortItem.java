package com.thaap.payments.simulation;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.UUID;

public record HistoricalSimulationCohortItem(
        UUID paymentAttemptId,
        UUID fraudDecisionId,
        UUID paymentIntentId,
        String paymentMethodFingerprint,
        Instant decisionCreatedAt,
        TrafficOrigin trafficOrigin,
        FraudDecisionAction originalAction,
        long authorizationAmount,
        long captureAmount,
        long refundAmount) { }
