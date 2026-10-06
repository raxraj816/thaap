package com.thaap.payments.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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

    // ER-0105. Readiness graded every tenant checkpoint by elapsed time alone, so a merchant who
    // simply stopped transacting for five minutes took the whole application DOWN. Wired to a
    // load balancer that removes every instance from rotation once traffic goes quiet — the
    // normal state of a demo or an off-peak deployment — and it worsens as tenants accumulate,
    // because one idle merchant is enough. These tests pin the distinction the indicator now
    // makes: age grades work that is waiting, and nothing is waiting on an idle tenant.

    private static PaymentRiskProjectionHealthIndicator indicator() {
        return new PaymentRiskProjectionHealthIndicator(mock(JdbcTemplate.class),
                Clock.fixed(NOW, ZoneOffset.UTC), true);
    }

    private static PaymentRiskProjectionHealthIndicator.TenantProjectionCheckpoint checkpoint(
            Instant lastProcessedAt, long pendingStreamCount) {
        return new PaymentRiskProjectionHealthIndicator.TenantProjectionCheckpoint(
                UUID.randomUUID(), "payment-risk-v1", lastProcessedAt, pendingStreamCount);
    }

    @Test
    void aCaughtUpTenantStaysFreshHoweverLongAgoItLastTransacted() {
        assertThat(indicator().readinessState(
                List.of(checkpoint(NOW.minus(Duration.ofDays(30)), 0)), 0))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.FRESH);
    }

    @Test
    void aTenantWithUnappliedStreamsAndAnOldCheckpointIsStale() {
        assertThat(indicator().readinessState(
                List.of(checkpoint(NOW.minus(Duration.ofMinutes(6)), 1)), 0))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.STALE);
    }

    @Test
    void oneIdleTenantCannotDragDownATenantThatIsGenuinelyLagging() {
        assertThat(indicator().readinessState(
                List.of(checkpoint(NOW.minus(Duration.ofDays(1)), 0),
                        checkpoint(NOW.minus(Duration.ofMinutes(2)), 3)), 0))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.LAGGING);
    }

    @Test
    void aTenantWithNoCheckpointIsUnavailableOnlyWhenWorkIsActuallyWaiting() {
        assertThat(indicator().readinessState(List.of(checkpoint(null, 2)), 0))
                .isEqualTo(
                        PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.UNAVAILABLE);
        assertThat(indicator().readinessState(List.of(checkpoint(null, 0)), 0))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.FRESH);
    }

    @Test
    void quarantineStillDegradesEvenWhenEveryTenantIsCaughtUp() {
        assertThat(indicator().readinessState(
                List.of(checkpoint(NOW.minus(Duration.ofDays(1)), 0)), 1))
                .isEqualTo(PaymentRiskProjectionHealthIndicator.ConsumerReadinessState.PARTIAL);
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
