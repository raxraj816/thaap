package com.thaap.payments.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentRiskProjectionHealthIndicatorTests {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void checkpointAgeUsesExactFreshLaggingAndStalePartitions() {
        assertThat(PaymentRiskProjectionHealthIndicator.checkpointAgeState(
                NOW, NOW.minusSeconds(60)))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.FRESH);
        assertThat(PaymentRiskProjectionHealthIndicator.checkpointAgeState(
                NOW, NOW.minusSeconds(60).minusNanos(1_000)))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.LAGGING);
        assertThat(PaymentRiskProjectionHealthIndicator.checkpointAgeState(
                NOW, NOW.minusSeconds(300)))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.LAGGING);
        assertThat(PaymentRiskProjectionHealthIndicator.checkpointAgeState(
                NOW, NOW.minusSeconds(300).minusNanos(1_000)))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.STALE);
        assertThat(PaymentRiskProjectionHealthIndicator.checkpointAgeState(
                NOW, NOW.plusNanos(1_000)))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.UNAVAILABLE);
    }

    @Test
    void explicitlyDisabledRequiredConsumerIsOutOfService() {
        PaymentRiskProjectionHealthIndicator indicator =
                new PaymentRiskProjectionHealthIndicator(mock(JdbcTemplate.class),
                        Clock.fixed(NOW, ZoneOffset.UTC), false);

        org.springframework.boot.health.contributor.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("consumerState", "DISABLED")
                .containsEntry("required", true);
    }
}
