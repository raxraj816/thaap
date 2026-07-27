package com.thaap.payments.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DisputeDeadlineSemanticsTests {

    @Test
    void beforeAndAtDeadlineAreAcceptedWhileAfterDeadlineIsRejected() {
        Instant dueAt = Instant.parse("2026-07-27T12:00:00Z");

        assertThat(DisputeOperationsService.isPastDeadline(dueAt.minusNanos(1), dueAt)).isFalse();
        assertThat(DisputeOperationsService.isPastDeadline(dueAt, dueAt)).isFalse();
        assertThat(DisputeOperationsService.isPastDeadline(dueAt.plusNanos(1), dueAt)).isTrue();
    }
}
