package com.thaap.payments.fraud;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record MaterializedFeatureSet(
        DecisionFeatureContext decisionFeatureContext,
        List<FeatureValue> values,
        Instant computedAt,
        Instant oldestFeatureTimestamp,
        long maxFeatureAgeMs,
        FeatureFreshnessStatus freshnessStatus,
        List<String> staleFeatureNames,
        List<String> missingFeatureNames) {

    public MaterializedFeatureSet {
        decisionFeatureContext = Objects.requireNonNull(decisionFeatureContext, "decisionFeatureContext");
        values = List.copyOf(values);
        Map<String, Object> typedValuesByCode = new TreeMap<>();
        for (FeatureValue value : values) {
            if (!FeatureCatalogue.VERSION.equals(value.getFeatureVersion())
                    || typedValuesByCode.putIfAbsent(value.getFeatureCode(), value.typedValue()) != null) {
                throw new IllegalArgumentException("Materialized feature values must contain one value per V1 code");
            }
        }
        if (!typedValuesByCode.equals(decisionFeatureContext.features())) {
            throw new IllegalArgumentException("Decision context and typed feature values must agree exactly");
        }
        computedAt = Objects.requireNonNull(computedAt, "computedAt");
        oldestFeatureTimestamp = Objects.requireNonNull(oldestFeatureTimestamp, "oldestFeatureTimestamp");
        freshnessStatus = Objects.requireNonNull(freshnessStatus, "freshnessStatus");
        staleFeatureNames = List.copyOf(staleFeatureNames);
        missingFeatureNames = List.copyOf(missingFeatureNames);
        if (maxFeatureAgeMs < 0) {
            throw new IllegalArgumentException("maxFeatureAgeMs must be non-negative");
        }
    }

    public Map<String, Object> snapshot() {
        return decisionFeatureContext.features();
    }
}
