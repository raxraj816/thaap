package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionFeatureContextTests {

    @Test
    void acceptsOnlyTheExactTypedGovernedCatalogueAndIsImmutable() {
        Map<String, Object> features = validFeatures();
        DecisionFeatureContext context = new DecisionFeatureContext(features);

        assertThat(context.features().keySet()).containsExactly(
                "card.country", "card.funding", "payment.amount_minor", "payment.attempt_number",
                "payment.capture_method", "payment.currency", "traffic.origin");
        assertThatThrownBy(() -> context.features().put("unsafe.bin", "424242"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> missing = validFeatures();
        missing.remove("traffic.origin");
        assertThatThrownBy(() -> new DecisionFeatureContext(missing))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> extra = validFeatures();
        extra.put("card.bin", "424242");
        assertThatThrownBy(() -> new DecisionFeatureContext(extra))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> wrongType = validFeatures();
        wrongType.put("payment.amount_minor", "1200");
        assertThatThrownBy(() -> new DecisionFeatureContext(wrongType))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> validFeatures() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("card.country", "US");
        features.put("card.funding", "CREDIT");
        features.put("payment.amount_minor", 1200L);
        features.put("payment.attempt_number", 1L);
        features.put("payment.capture_method", "MANUAL");
        features.put("payment.currency", "USD");
        features.put("traffic.origin", "MERCHANT_API");
        return features;
    }
}
