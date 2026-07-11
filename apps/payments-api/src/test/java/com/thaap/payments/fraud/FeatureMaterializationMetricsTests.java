package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.tenant.TrafficOrigin;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class FeatureMaterializationMetricsTests {

    @Test
    void metricsUseOnlyBoundedOperationalDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeatureMaterializationMetrics metrics = new FeatureMaterializationMetrics(registry);

        metrics.recordFreshness(FeatureFreshnessStatus.FRESH, TrafficOrigin.MERCHANT_API);
        metrics.recordFailure("VALIDATION", "INVALID_CATALOGUE_OR_VALUE", "payment.currency");
        metrics.recordMaterializationLatency("SUCCESS", TrafficOrigin.MERCHANT_API, 1_000_000L);

        assertThat(registry.get("fraud_feature_freshness_total")
                .tags("status", "FRESH", "traffic_origin", "MERCHANT_API").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud_decision_failures_total")
                .tags("stage", "VALIDATION", "cause", "INVALID_CATALOGUE_OR_VALUE",
                        "feature_code", "payment.currency").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("fraud_feature_materialization_seconds")
                .tags("outcome", "SUCCESS", "traffic_origin", "MERCHANT_API").timer().count()).isEqualTo(1L);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .doesNotContain("merchant_id", "payment_intent_id", "payment_attempt_id", "feature_value",
                        "idempotency_key");
        assertThat(registry.getMeters()).extracting(Meter::getId).allSatisfy(id ->
                assertThat(id.getName()).startsWith("fraud_"));
    }
}
