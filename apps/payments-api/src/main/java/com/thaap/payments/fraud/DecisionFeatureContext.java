package com.thaap.payments.fraud;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The complete, safe, governed feature input available to a confirm decision.
 * Payment and processor orchestration identifiers deliberately stay outside
 * this boundary so every value the engine can inspect is persisted as evidence.
 */
public record DecisionFeatureContext(Map<String, Object> features) {

    public DecisionFeatureContext {
        Objects.requireNonNull(features, "features");
        Map<String, Object> lexicalCopy = new TreeMap<>(features);
        if (!lexicalCopy.keySet().equals(FeatureCatalogue.requiredCodes())) {
            throw new IllegalArgumentException("Decision feature context must contain the exact V1 catalogue");
        }
        for (Map.Entry<String, Object> entry : lexicalCopy.entrySet()) {
            Object value = Objects.requireNonNull(entry.getValue(), "feature value");
            if (!matchesRequiredType(FeatureCatalogue.requiredType(entry.getKey()), value)) {
                throw new IllegalArgumentException("Decision feature context contains an incompatible value type");
            }
        }
        features = Collections.unmodifiableMap(new LinkedHashMap<>(lexicalCopy));
    }

    private static boolean matchesRequiredType(FeatureValueType requiredType, Object value) {
        return switch (requiredType) {
            case LONG -> value instanceof Long;
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case DECIMAL -> value instanceof BigDecimal;
        };
    }
}
