package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.tenant.TrafficOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FeatureValueBoundaryTests {

    private static final Instant DECISION_START = Instant.parse("2026-07-11T12:00:00Z");

    @Test
    void rejectsEveryFeatureTypeAndSizeBoundaryPartition() {
        FeatureDefinition stringDefinition = definition("test.string", FeatureValueType.STRING);
        assertThat(value(stringDefinition, "a").typedValue()).isEqualTo("a");
        assertThat(value(stringDefinition, "a".repeat(256)).typedValue()).isEqualTo("a".repeat(256));
        assertThat(value(stringDefinition, "é".repeat(128)).typedValue()).isEqualTo("é".repeat(128));
        assertThatThrownBy(() -> value(stringDefinition, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value(stringDefinition, "a".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value(stringDefinition, "é".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value(stringDefinition, 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(value(definition("test.long", FeatureValueType.LONG), Long.MIN_VALUE).typedValue())
                .isEqualTo(Long.MIN_VALUE);
        assertThat(value(definition("test.long", FeatureValueType.LONG), Long.MAX_VALUE).typedValue())
                .isEqualTo(Long.MAX_VALUE);
        assertThat(value(definition("test.boolean", FeatureValueType.BOOLEAN), Boolean.TRUE).typedValue())
                .isEqualTo(Boolean.TRUE);
        assertThat((BigDecimal) value(definition("test.decimal", FeatureValueType.DECIMAL),
                new BigDecimal("1.250000")).typedValue())
                .isEqualByComparingTo("1.250000");
        assertThatThrownBy(() -> value(definition("test.decimal", FeatureValueType.DECIMAL),
                new BigDecimal("1.0000001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value(definition("test.decimal", FeatureValueType.DECIMAL),
                new BigDecimal("10000000000000.000000"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void futureAndRepresentationBoundaryViolationsFailBeforePersistence() {
        FeatureDefinition definition = definition("test.long", FeatureValueType.LONG);
        assertThatThrownBy(() -> new FeatureValue(UUID.randomUUID(), UUID.randomUUID(), definition, 1L,
                DECISION_START.plusNanos(1_000), DECISION_START.plusSeconds(1), DECISION_START,
                TrafficOrigin.MERCHANT_API)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after decision start");
        assertThatThrownBy(() -> new FeatureValue(UUID.randomUUID(), UUID.randomUUID(), definition, 1L,
                DECISION_START.minusSeconds(1), DECISION_START.minusSeconds(2), DECISION_START,
                TrafficOrigin.MERCHANT_API)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after computed time");
        assertThatThrownBy(() -> value(definition, new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotConverterRoundTripPreservesLongAndDecimalDomainTypes() {
        FeatureMapConverter converter = new FeatureMapConverter();
        java.util.Map<String, Object> roundTrip = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(java.util.Map.of(
                        "test.long", 1L,
                        "test.decimal", new BigDecimal("1.250000"))));

        assertThat(roundTrip.get("test.long")).isInstanceOf(Long.class).isEqualTo(1L);
        assertThat(roundTrip.get("test.decimal")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) roundTrip.get("test.decimal")).isEqualByComparingTo("1.250000");
    }

    private FeatureValue value(FeatureDefinition definition, Object value) {
        return new FeatureValue(UUID.randomUUID(), UUID.randomUUID(), definition, value,
                DECISION_START.minusSeconds(1), DECISION_START, DECISION_START, TrafficOrigin.MERCHANT_API);
    }

    private FeatureDefinition definition(String code, FeatureValueType type) {
        FeatureDefinition definition = new FeatureDefinition();
        ReflectionTestUtils.setField(definition, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(definition, "featureCode", code);
        ReflectionTestUtils.setField(definition, "featureVersion", "v1");
        ReflectionTestUtils.setField(definition, "entityType", FeatureEntityType.PAYMENT_ATTEMPT);
        ReflectionTestUtils.setField(definition, "valueType", type);
        return definition;
    }
}
