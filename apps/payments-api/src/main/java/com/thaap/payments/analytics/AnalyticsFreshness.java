package com.thaap.payments.analytics;

import java.time.Instant;
import java.util.UUID;

public record AnalyticsFreshness(
        UUID generationId,
        long generationNumber,
        Instant sourceCutoff,
        Instant lastAppliedEventTime,
        Instant lastProcessingTime,
        AnalyticsFreshnessState state,
        long eligibleEventCount,
        long appliedEventCount,
        long unavailableStreamCount,
        String trafficOriginPolicy) {
}
