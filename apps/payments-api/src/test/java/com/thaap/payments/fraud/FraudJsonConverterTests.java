package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FraudJsonConverterTests {

    private final FeatureMapConverter featureMapConverter = new FeatureMapConverter();
    private final FraudDecisionReasonCodeListConverter reasonCodeListConverter =
            new FraudDecisionReasonCodeListConverter();
    private final StringListConverter stringListConverter = new StringListConverter();

    @Test
    void featureMapConverterHandlesNullAndBlankValues() {
        assertThat(featureMapConverter.convertToDatabaseColumn(null)).isEqualTo("{}");
        assertThat(featureMapConverter.convertToEntityAttribute(null)).isEmpty();
        assertThat(featureMapConverter.convertToEntityAttribute("")).isEmpty();
        assertThat(featureMapConverter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void featureMapConverterRoundTripsAndSerializesDeterministically() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("z_feature", "last");
        features.put("a_feature", 42);

        String serialized = featureMapConverter.convertToDatabaseColumn(features);
        Map<String, Object> restored = featureMapConverter.convertToEntityAttribute(serialized);

        assertThat(serialized).isEqualTo("{\"a_feature\":42,\"z_feature\":\"last\"}");
        assertThat(restored).containsEntry("a_feature", 42).containsEntry("z_feature", "last");
    }

    @Test
    void fraudDecisionReasonCodeListConverterHandlesNullBlankAndRoundTrip() {
        assertThat(reasonCodeListConverter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(reasonCodeListConverter.convertToEntityAttribute(null)).isEmpty();
        assertThat(reasonCodeListConverter.convertToEntityAttribute("")).isEmpty();
        assertThat(reasonCodeListConverter.convertToEntityAttribute("   ")).isEmpty();

        String serialized = reasonCodeListConverter.convertToDatabaseColumn(
                List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED));

        assertThat(serialized).isEqualTo("[\"RULES_NOT_ENABLED\"]");
        assertThat(reasonCodeListConverter.convertToEntityAttribute(serialized))
                .containsExactly(FraudDecisionReasonCode.RULES_NOT_ENABLED);
    }

    @Test
    void stringListConverterHandlesNullBlankAndRoundTrip() {
        assertThat(stringListConverter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(stringListConverter.convertToEntityAttribute(null)).isEmpty();
        assertThat(stringListConverter.convertToEntityAttribute("")).isEmpty();
        assertThat(stringListConverter.convertToEntityAttribute("   ")).isEmpty();

        String serialized = stringListConverter.convertToDatabaseColumn(List.of("card_bin", "card_country"));

        assertThat(serialized).isEqualTo("[\"card_bin\",\"card_country\"]");
        assertThat(stringListConverter.convertToEntityAttribute(serialized))
                .containsExactly("card_bin", "card_country");
    }
}
