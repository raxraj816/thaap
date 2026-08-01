package com.thaap.payments.analytics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Pure, clock-controlled implementation of the frozen analytics freshness partitions. */
@Component
public class AnalyticsFreshnessEvaluator {

    private final Clock clock;

    public AnalyticsFreshnessEvaluator(Clock clock) {
        this.clock = clock;
    }

    public AnalyticsFreshnessState evaluate(long eligible, long applied,
            long unavailable, Instant lastProcessed) {
        if (unavailable > 0 || applied < eligible) {
            return AnalyticsFreshnessState.PARTIAL;
        }
        if (eligible == 0) {
            return AnalyticsFreshnessState.EMPTY;
        }
        if (lastProcessed == null) {
            return AnalyticsFreshnessState.UNAVAILABLE;
        }
        Duration age = Duration.between(lastProcessed, clock.instant());
        if (age.isNegative()) {
            return AnalyticsFreshnessState.UNAVAILABLE;
        }
        if (age.compareTo(Duration.ofSeconds(60)) <= 0) {
            return AnalyticsFreshnessState.FRESH;
        }
        if (age.compareTo(Duration.ofMinutes(5)) <= 0) {
            return AnalyticsFreshnessState.LAGGING;
        }
        return AnalyticsFreshnessState.STALE;
    }
}
