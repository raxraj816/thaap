package com.thaap.payments.fraud;

import com.thaap.payments.tenant.TrafficOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class FeatureMaterializationMetrics {

    private final MeterRegistry meterRegistry;

    public FeatureMaterializationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordFreshness(FeatureFreshnessStatus status, TrafficOrigin trafficOrigin) {
        meterRegistry.counter("fraud_feature_freshness_total",
                "status", status.name(), "traffic_origin", trafficOrigin.name()).increment();
    }

    public void recordFailure(String stage, String cause) {
        recordFailure(stage, cause, "NONE");
    }

    public void recordFailure(String stage, String cause, String featureCode) {
        meterRegistry.counter("fraud_decision_failures_total",
                "stage", stage, "cause", cause, "feature_code", featureCode).increment();
    }

    public void recordMaterializationLatency(String outcome, TrafficOrigin trafficOrigin, long elapsedNanos) {
        meterRegistry.timer("fraud_feature_materialization_seconds",
                "outcome", outcome, "traffic_origin", trafficOrigin.name())
                .record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }
}
