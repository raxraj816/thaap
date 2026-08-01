package com.thaap.payments.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AnalyticsFreshnessEvaluatorTests {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private final AnalyticsFreshnessEvaluator evaluator = new AnalyticsFreshnessEvaluator(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exactAgePartitionsAndNonAgeStatesRemainDistinct() {
        assertThat(evaluator.evaluate(1, 1, 0, NOW.minusSeconds(60)))
                .isEqualTo(AnalyticsFreshnessState.FRESH);
        assertThat(evaluator.evaluate(1, 1, 0,
                NOW.minusSeconds(60).minusNanos(1_000)))
                .isEqualTo(AnalyticsFreshnessState.LAGGING);
        assertThat(evaluator.evaluate(1, 1, 0, NOW.minusSeconds(300)))
                .isEqualTo(AnalyticsFreshnessState.LAGGING);
        assertThat(evaluator.evaluate(1, 1, 0,
                NOW.minusSeconds(300).minusNanos(1_000)))
                .isEqualTo(AnalyticsFreshnessState.STALE);

        assertThat(evaluator.evaluate(0, 0, 0, null))
                .isEqualTo(AnalyticsFreshnessState.EMPTY);
        assertThat(evaluator.evaluate(0, 0, 1, null))
                .isEqualTo(AnalyticsFreshnessState.PARTIAL);
        assertThat(evaluator.evaluate(2, 1, 0, NOW))
                .isEqualTo(AnalyticsFreshnessState.PARTIAL);
        assertThat(evaluator.evaluate(1, 1, 1, NOW))
                .isEqualTo(AnalyticsFreshnessState.PARTIAL);
        assertThat(evaluator.evaluate(1, 1, 0, null))
                .isEqualTo(AnalyticsFreshnessState.UNAVAILABLE);
        assertThat(evaluator.evaluate(1, 1, 0, NOW.plusNanos(1_000)))
                .isEqualTo(AnalyticsFreshnessState.UNAVAILABLE);
    }
}
