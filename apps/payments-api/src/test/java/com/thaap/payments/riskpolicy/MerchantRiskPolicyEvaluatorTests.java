package com.thaap.payments.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.merchant.MerchantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

class MerchantRiskPolicyEvaluatorTests {

    private final MerchantRiskPolicyEvaluator evaluator = new MerchantRiskPolicyEvaluator(
            mock(MerchantRepository.class), mock(JdbcTemplate.class),
            new MerchantRiskPolicyMetrics(new SimpleMeterRegistry()));

    @ParameterizedTest
    @MethodSource("matchPartitions")
    void everySupportedTypeOperatorAndBoundaryHasExactFixtureSemantics(
            MerchantPolicyThreshold threshold, Object value, String currency, boolean expected) {
        assertThat(evaluator.matchesFixture(threshold, value, currency)).isEqualTo(expected);
    }

    @Test
    void missingWrongTypedAndCurrencyMismatchedValuesNeverMatch() {
        MerchantPolicyThreshold threshold = threshold(FeatureValueType.LONG, PolicyThresholdOperator.GTE,
                100L, null, List.of(), "USD");

        assertThat(evaluator.matchesFixture(threshold, null, "USD")).isFalse();
        assertThat(evaluator.matchesFixture(threshold, "100", "USD")).isFalse();
        assertThat(evaluator.matchesFixture(threshold, 100L, "EUR")).isFalse();
        assertThat(evaluator.matchesFixture(threshold, 100L, null)).isFalse();
    }

    private static Stream<Arguments> matchPartitions() {
        return Stream.of(
                Arguments.of(threshold(FeatureValueType.LONG, PolicyThresholdOperator.GTE,
                        100L, null, List.of(), null), 99L, "USD", false),
                Arguments.of(threshold(FeatureValueType.LONG, PolicyThresholdOperator.GTE,
                        100L, null, List.of(), null), 100L, "USD", true),
                Arguments.of(threshold(FeatureValueType.LONG, PolicyThresholdOperator.GTE,
                        100L, null, List.of(), null), 101L, "USD", true),
                Arguments.of(threshold(FeatureValueType.LONG, PolicyThresholdOperator.EQ,
                        100L, null, List.of(), null), 100L, "USD", true),
                Arguments.of(threshold(FeatureValueType.LONG, PolicyThresholdOperator.EQ,
                        100L, null, List.of(), null), 101L, "USD", false),
                Arguments.of(threshold(FeatureValueType.BOOLEAN, PolicyThresholdOperator.EQ,
                        null, true, List.of(), null), true, "USD", true),
                Arguments.of(threshold(FeatureValueType.BOOLEAN, PolicyThresholdOperator.EQ,
                        null, true, List.of(), null), false, "USD", false),
                Arguments.of(threshold(FeatureValueType.STRING, PolicyThresholdOperator.EQ,
                        null, null, List.of("HIGH"), null), "HIGH", "USD", true),
                Arguments.of(threshold(FeatureValueType.STRING, PolicyThresholdOperator.EQ,
                        null, null, List.of("HIGH"), null), "high", "USD", false),
                Arguments.of(threshold(FeatureValueType.STRING, PolicyThresholdOperator.IN,
                        null, null, List.of("HIGH", "ELEVATED"), null), "ELEVATED", "USD", true),
                Arguments.of(threshold(FeatureValueType.STRING, PolicyThresholdOperator.IN,
                        null, null, List.of("HIGH", "ELEVATED"), null), "STANDARD", "USD", false),
                Arguments.of(threshold(FeatureValueType.LONG, PolicyThresholdOperator.GTE,
                        100L, null, List.of(), "USD"), 100L, "USD", true));
    }

    private static MerchantPolicyThreshold threshold(FeatureValueType type, PolicyThresholdOperator operator,
            Long longOperand, Boolean booleanOperand, List<String> stringOperands, String currency) {
        return new MerchantPolicyThreshold(UUID.randomUUID(), UUID.randomUUID(), "THRESHOLD",
                UUID.randomUUID(), "payment.test", "v1", type, operator, longOperand,
                booleanOperand, stringOperands, FraudDecisionAction.REVIEW, 10,
                PolicyThresholdReasonCode.MERCHANT_CONTEXT_THRESHOLD, currency);
    }
}
