package com.thaap.payments.workspace;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("analystWorkspace")
public class AnalystWorkspaceHealthIndicator implements HealthIndicator {

    private final ReviewNoteKeyConfiguration keyConfiguration;
    private final AnalystWorkspaceMetrics metrics;

    public AnalystWorkspaceHealthIndicator(ReviewNoteKeyConfiguration keyConfiguration,
            AnalystWorkspaceMetrics metrics) {
        this.keyConfiguration = keyConfiguration;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        try {
            keyConfiguration.validateRequiredKeys();
            return Health.up().build();
        } catch (RuntimeException exception) {
            metrics.readinessFailure("NOTE_KEY_UNAVAILABLE");
            return Health.down().build();
        }
    }
}
