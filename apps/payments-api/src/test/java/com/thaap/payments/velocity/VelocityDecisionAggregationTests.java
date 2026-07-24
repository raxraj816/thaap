package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import com.thaap.payments.fraudlist.FraudListAssessment;
import com.thaap.payments.fraudlist.FraudListDecisionAggregator;
import com.thaap.payments.fraudlist.FraudListEvaluation;
import com.thaap.payments.fraudlist.FraudListIdentityType;
import com.thaap.payments.fraudlist.FraudListReasonCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VelocityDecisionAggregationTests {

    private final VelocityDecisionAggregator velocityAggregator = new VelocityDecisionAggregator();
    private final FraudListDecisionAggregator listAggregator = new FraudListDecisionAggregator();

    @ParameterizedTest(name = "base={0}, velocity={1}, list={2}, expected={3}")
    @MethodSource("completeCandidateSet")
    void completeBaseVelocityAndListCandidateSetHasOneProtectedPrecedence(
            FraudDecisionAction baseAction, VelocityState velocityState,
            FraudDecisionAction listAction, FraudDecisionAction expected) {
        ConfirmDecision base = decision(baseAction);

        ConfirmDecision withVelocity = velocityAggregator.aggregate(base, velocity(velocityState));
        ConfirmDecision result = listAggregator.aggregate(withVelocity,
                new FraudListAssessment(List.of(evaluation(listAction))));

        assertThat(result.action()).isEqualTo(expected);
        assertThat(result.reasonCodes()).contains(FraudDecisionReasonCode.NO_RULE_MATCH,
                FraudDecisionReasonCode.FRAUD_LIST_MATCH);
        if (velocityState == VelocityState.UNAVAILABLE) {
            assertThat(result.reasonCodes()).contains(FraudDecisionReasonCode.VELOCITY_UNAVAILABLE);
        } else if (velocityState == VelocityState.THRESHOLD) {
            assertThat(result.reasonCodes()).contains(FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH);
        }
    }

    @ParameterizedTest
    @MethodSource("listInsertionOrders")
    void multipleListMatchesAreInsertionOrderIndependentAndReasonIsAddedOnce(List<FraudDecisionAction> order) {
        List<FraudListEvaluation> evaluations = order.stream().map(this::evaluation).toList();

        ConfirmDecision result = listAggregator.aggregate(decision(FraudDecisionAction.ALLOW),
                new FraudListAssessment(evaluations));

        assertThat(result.action()).isEqualTo(FraudDecisionAction.BLOCK);
        assertThat(Collections.frequency(result.reasonCodes(), FraudDecisionReasonCode.FRAUD_LIST_MATCH)).isOne();
    }

    static Stream<Arguments> completeCandidateSet() {
        return Stream.of(FraudDecisionAction.values()).flatMap(base ->
                Stream.of(VelocityState.values()).flatMap(velocity ->
                        Stream.of(FraudDecisionAction.BLOCK, FraudDecisionAction.REVIEW,
                                        FraudDecisionAction.REQUEST_3DS)
                                .map(list -> Arguments.of(base, velocity, list,
                                        strongest(base, velocity == VelocityState.NONE ? null
                                                : FraudDecisionAction.REVIEW, list)))));
    }

    static Stream<Arguments> listInsertionOrders() {
        List<FraudDecisionAction> actions = List.of(FraudDecisionAction.REQUEST_3DS,
                FraudDecisionAction.REVIEW, FraudDecisionAction.BLOCK);
        List<List<FraudDecisionAction>> permutations = new ArrayList<>();
        permute(new ArrayList<>(actions), 0, permutations);
        return permutations.stream().map(Arguments::of);
    }

    private static void permute(List<FraudDecisionAction> values, int index,
            List<List<FraudDecisionAction>> permutations) {
        if (index == values.size()) {
            permutations.add(List.copyOf(values));
            return;
        }
        for (int candidate = index; candidate < values.size(); candidate++) {
            Collections.swap(values, index, candidate);
            permute(values, index + 1, permutations);
            Collections.swap(values, index, candidate);
        }
    }

    private VelocityAssessment velocity(VelocityState state) {
        if (state == VelocityState.NONE) {
            return VelocityAssessment.disabled();
        }
        VelocitySignal signal = state == VelocityState.UNAVAILABLE
                ? VelocitySignal.unavailable(VelocityDimension.CARD_ATTEMPTS_10M,
                        VelocityUnavailableException.Cause.DEPENDENCY_FAILURE)
                : VelocitySignal.available(VelocityDimension.CARD_ATTEMPTS_10M,
                        new VelocityObservation(VelocityScriptContract.VERSION, 1_720_000_000_123L,
                                VelocityDimension.CARD_ATTEMPTS_10M.reviewThreshold(),
                                VelocityObservation.ExecutionMode.FIRST));
        return new VelocityAssessment(List.of(signal), List.of(mock(VelocityObservationRecord.class)), List.of());
    }

    private FraudListEvaluation evaluation(FraudDecisionAction action) {
        return new FraudListEvaluation(UUID.randomUUID(), UUID.randomUUID(), 1, "CLOSED_LIST_CODE",
                FraudListIdentityType.CARD_FINGERPRINT, UUID.randomUUID(), action,
                FraudListReasonCode.KNOWN_FRAUD, Instant.parse("2026-07-14T00:00:00Z"));
    }

    private ConfirmDecision decision(FraudDecisionAction action) {
        return new ConfirmDecision(action, score(action), risk(action), "rules_v1", null,
                List.of(FraudDecisionReasonCode.NO_RULE_MATCH));
    }

    private static FraudDecisionAction strongest(FraudDecisionAction... actions) {
        FraudDecisionAction strongest = FraudDecisionAction.ALLOW;
        for (FraudDecisionAction action : actions) {
            if (action != null && strength(action) > strength(strongest)) {
                strongest = action;
            }
        }
        return strongest;
    }

    private static int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
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

    enum VelocityState {
        NONE,
        UNAVAILABLE,
        THRESHOLD
    }
}
