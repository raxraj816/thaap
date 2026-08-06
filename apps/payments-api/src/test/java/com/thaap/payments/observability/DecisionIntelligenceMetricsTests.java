package com.thaap.payments.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DecisionIntelligenceMetricsTests {

    @Test
    void recordsOnlyBoundedOperationalDimensionsAndBacklogGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);

        metrics.request("INVESTIGATION_DETAIL", "SUCCEEDED", 1_000);
        metrics.queryResult("TIMELINE", 11, "PARTIAL");
        metrics.section("DECISION_EXPLANATION", "AVAILABLE");
        metrics.version("EXACT");
        metrics.bulkItem("STALE");
        metrics.bulkLease("RECOVERED");
        metrics.bulkBacklog(7, Duration.ofSeconds(90));

        assertThat(registry.get("thaap.decision_intelligence.requests")
                .tag("operation", "INVESTIGATION_DETAIL").tag("outcome", "SUCCEEDED")
                .timer().count()).isOne();
        assertThat(registry.get("thaap.decision_intelligence.query_results")
                .tag("operation", "TIMELINE").tag("size", "ELEVEN_TO_TWENTY_FIVE")
                .tag("coverage", "PARTIAL").counter().count()).isOne();
        assertThat(registry.get("thaap.decision_intelligence.bulk.backlog").gauge().value())
                .isEqualTo(7);
        assertThat(registry.get("thaap.decision_intelligence.bulk.oldest_age_seconds")
                .gauge().value()).isEqualTo(90);
    }

    @Test
    void rejectsUnboundedOrUnrecognisedMetricTagsBeforeRegistration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);

        assertThatThrownBy(() -> metrics.request("/merchant/supplied/path", "SUCCEEDED", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
        assertThatThrownBy(() -> metrics.section("PAYMENT_LIFECYCLE", "merchant-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
        assertThat(registry.find("thaap.decision_intelligence.requests").meter()).isNull();
    }
}
