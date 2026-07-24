package com.thaap.payments.fraudlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FraudListDecisionAggregatorTests {

    private final FraudListDecisionAggregator aggregator = new FraudListDecisionAggregator();

    @ParameterizedTest
    @MethodSource("actionPermutations")
    void completePrecedenceNeverWeakensProtectedBaseAction(FraudDecisionAction base,
            FraudDecisionAction listAction, FraudDecisionAction expected) {
        ConfirmDecision decision = new ConfirmDecision(base, new BigDecimal("0.0500"), FraudRiskLevel.LOW,
                "rules_v1", null, List.of(FraudDecisionReasonCode.NO_RULE_MATCH));
        FraudListEvaluation evaluation = new FraudListEvaluation(UUID.randomUUID(), UUID.randomUUID(), 1,
                "LIST", FraudListIdentityType.CARD_FINGERPRINT, UUID.randomUUID(), listAction,
                FraudListReasonCode.KNOWN_FRAUD, Instant.parse("2026-07-14T00:00:00Z"));

        ConfirmDecision result = aggregator.aggregate(decision, new FraudListAssessment(List.of(evaluation)));

        assertThat(result.action()).isEqualTo(expected);
        assertThat(result.reasonCodes()).containsExactly(
                FraudDecisionReasonCode.NO_RULE_MATCH, FraudDecisionReasonCode.FRAUD_LIST_MATCH);
    }

    static Stream<Arguments> actionPermutations() {
        return Stream.of(FraudDecisionAction.values()).flatMap(base -> Stream.of(
                FraudDecisionAction.BLOCK, FraudDecisionAction.REVIEW, FraudDecisionAction.REQUEST_3DS)
                .map(list -> Arguments.of(base, list, stronger(base, list))));
    }

    private static FraudDecisionAction stronger(FraudDecisionAction left, FraudDecisionAction right) {
        return strength(left) >= strength(right) ? left : right;
    }

    private static int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }
}
