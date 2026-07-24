package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VelocityDecisionAggregatorTests {

    private final VelocityDecisionAggregator aggregator = new VelocityDecisionAggregator();

    @ParameterizedTest
    @MethodSource("precedenceCases")
    void reviewFloorNeverWeakensBaseDecision(FraudDecisionAction baseAction, FraudDecisionAction expectedAction) {
        VelocityAssessment assessment = unavailableAssessment();
        ConfirmDecision base = new ConfirmDecision(baseAction, score(baseAction), risk(baseAction),
                "rules_v1", null, List.of(FraudDecisionReasonCode.NO_RULE_MATCH), List.of());

        ConfirmDecision result = aggregator.aggregate(base, assessment);

        assertThat(result.action()).isEqualTo(expectedAction);
        assertThat(result.reasonCodes()).containsExactly(
                FraudDecisionReasonCode.NO_RULE_MATCH, FraudDecisionReasonCode.VELOCITY_UNAVAILABLE);
        assertThat(result.rulesetVersion()).isEqualTo("rules_v1");
    }

    @ParameterizedTest
    @MethodSource("precedenceCases")
    void thresholdCandidateUsesTheSameProtectedPrecedence(FraudDecisionAction baseAction,
            FraudDecisionAction expectedAction) {
        VelocityAssessment assessment = thresholdAssessment();
        ConfirmDecision base = new ConfirmDecision(baseAction, score(baseAction), risk(baseAction),
                "rules_v1", null, List.of(FraudDecisionReasonCode.NO_RULE_MATCH), List.of());

        ConfirmDecision result = aggregator.aggregate(base, assessment);

        assertThat(result.action()).isEqualTo(expectedAction);
        assertThat(result.reasonCodes()).containsExactly(
                FraudDecisionReasonCode.NO_RULE_MATCH, FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH);
    }

    static Stream<Arguments> precedenceCases() {
        return Stream.of(
                Arguments.of(FraudDecisionAction.ALLOW, FraudDecisionAction.REVIEW),
                Arguments.of(FraudDecisionAction.REQUEST_3DS, FraudDecisionAction.REVIEW),
                Arguments.of(FraudDecisionAction.REVIEW, FraudDecisionAction.REVIEW),
                Arguments.of(FraudDecisionAction.BLOCK, FraudDecisionAction.BLOCK));
    }

    private VelocityAssessment unavailableAssessment() {
        return new VelocityAssessment(List.of(VelocitySignal.unavailable(
                VelocityDimension.CARD_ATTEMPTS_10M, VelocityUnavailableException.Cause.DEPENDENCY_FAILURE)),
                List.of(mock(VelocityObservationRecord.class)), List.of());
    }

    private VelocityAssessment thresholdAssessment() {
        VelocityObservation observation = new VelocityObservation(VelocityScriptContract.VERSION,
                1_720_000_000_123L, VelocityDimension.CARD_ATTEMPTS_10M.reviewThreshold(),
                VelocityObservation.ExecutionMode.FIRST);
        return new VelocityAssessment(List.of(VelocitySignal.available(
                VelocityDimension.CARD_ATTEMPTS_10M, observation)),
                List.of(mock(VelocityObservationRecord.class)), List.of());
    }

    private static BigDecimal score(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> new BigDecimal("0.0500");
            case REQUEST_3DS -> new BigDecimal("0.5500");
            case REVIEW -> new BigDecimal("0.7500");
            case BLOCK -> new BigDecimal("0.9500");
        };
    }

    private static FraudRiskLevel risk(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> FraudRiskLevel.LOW;
            case REQUEST_3DS -> FraudRiskLevel.MEDIUM;
            case REVIEW, BLOCK -> FraudRiskLevel.HIGH;
        };
    }
}
