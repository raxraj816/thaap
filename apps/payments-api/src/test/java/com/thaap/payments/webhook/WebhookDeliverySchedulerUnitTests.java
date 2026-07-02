package com.thaap.payments.webhook;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookDeliverySchedulerUnitTests {

    private static final Instant POLL_TIME = Instant.parse("2026-07-02T00:00:00Z");

    @Test
    void disabledExecutionRefreshesDueBacklogWithoutClaimingWork() {
        WebhookDeliveryExecutor executor = mock(WebhookDeliveryExecutor.class);
        WebhookDeliveryScheduler scheduler = new WebhookDeliveryScheduler(
                executor, Clock.fixed(POLL_TIME, ZoneOffset.UTC), false, 20);

        scheduler.poll();

        verify(executor).refreshDueDeliveries(POLL_TIME);
        verify(executor, never()).processDueDeliveries(POLL_TIME, 20);
    }

    @Test
    void enabledExecutionProcessesDueWorkAndRefreshesBacklogThroughExecutor() {
        WebhookDeliveryExecutor executor = mock(WebhookDeliveryExecutor.class);
        WebhookDeliveryScheduler scheduler = new WebhookDeliveryScheduler(
                executor, Clock.fixed(POLL_TIME, ZoneOffset.UTC), true, 20);

        scheduler.poll();

        verify(executor).processDueDeliveries(POLL_TIME, 20);
        verify(executor, never()).refreshDueDeliveries(POLL_TIME);
    }
}
