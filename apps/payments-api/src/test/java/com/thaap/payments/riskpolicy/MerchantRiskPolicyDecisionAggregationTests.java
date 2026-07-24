package com.thaap.payments.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantRiskPolicyDecisionAggregationTests {

    private final MerchantRiskPolicyDecisionAggregator aggregator = new MerchantRiskPolicyDecisionAggregator();

    @Test
    void everyBaseAndPolicyCandidateCombinationPreservesTheStrongerAction() {
        for (FraudDecisionAction base : FraudDecisionAction.values()) {
            for (FraudDecisionAction candidate : List.of(
                    FraudDecisionAction.REQUEST_3DS, FraudDecisionAction.REVIEW, FraudDecisionAction.BLOCK)) {
                ConfirmDecision result = aggregator.aggregate(decision(base), assessment(List.of(candidate)));
                assertThat(result.action()).isEqualTo(strength(base) >= strength(candidate) ? base : candidate);
                assertThat(result.reasonCodes()).contains(FraudDecisionReasonCode.MERCHANT_POLICY_MATCH);
            }
            ConfirmDecision unchanged = aggregator.aggregate(decision(base), assessment(List.of()));
            assertThat(unchanged).isEqualTo(decision(base));
        }
    }

    @Test
    void everyInsertionOrderSelectsBlockAndUnavailableAlwaysSuppliesReviewFloor() {
        List<FraudDecisionAction> actions = List.of(
                FraudDecisionAction.REQUEST_3DS, FraudDecisionAction.REVIEW, FraudDecisionAction.BLOCK);
        for (List<FraudDecisionAction> permutation : permutations(actions)) {
            ConfirmDecision result = aggregator.aggregate(decision(FraudDecisionAction.ALLOW),
                    assessment(permutation));
            assertThat(result.action()).isEqualTo(FraudDecisionAction.BLOCK);
        }
        MerchantRiskConfiguration unavailable = MerchantRiskConfiguration.unavailable(
                UUID.randomUUID(), Instant.parse("2026-07-14T00:00:00Z"));
        ConfirmDecision allow = aggregator.aggregate(decision(FraudDecisionAction.ALLOW),
                new MerchantPolicyAssessment(unavailable, List.of()));
        ConfirmDecision block = aggregator.aggregate(decision(FraudDecisionAction.BLOCK),
                new MerchantPolicyAssessment(unavailable, List.of()));
        assertThat(allow.action()).isEqualTo(FraudDecisionAction.REVIEW);
        assertThat(block.action()).isEqualTo(FraudDecisionAction.BLOCK);
        assertThat(allow.reasonCodes()).contains(FraudDecisionReasonCode.MERCHANT_POLICY_UNAVAILABLE);
    }

    private MerchantPolicyAssessment assessment(List<FraudDecisionAction> actions) {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        MerchantRiskConfiguration configuration = new MerchantRiskConfiguration(merchantId,
                MerchantRiskConfigurationStatus.ACTIVE, null, MerchantRiskTier.STANDARD,
                MerchantRiskProfileSource.APPLICATION_DEFAULT, policyId, "TEST_POLICY", 1,
                List.of(), Instant.parse("2026-07-14T00:00:00Z"));
        List<MerchantPolicyEvaluation> evaluations = new ArrayList<>();
        int priority = 0;
        for (FraudDecisionAction action : actions) {
            MerchantPolicyThreshold threshold = new MerchantPolicyThreshold(UUID.randomUUID(), policyId,
                    action.name() + "_" + priority, UUID.randomUUID(), "merchant.risk_tier", "v1",
                    FeatureValueType.STRING, PolicyThresholdOperator.EQ, null, null,
                    List.of("STANDARD"), action, priority++, PolicyThresholdReasonCode.MERCHANT_RISK_TIER, null);
            evaluations.add(new MerchantPolicyEvaluation(threshold, null, true));
        }
        return new MerchantPolicyAssessment(configuration, evaluations);
    }

    private ConfirmDecision decision(FraudDecisionAction action) {
        return new ConfirmDecision(action, score(action), risk(action), "rules_v1", null,
                List.of(FraudDecisionReasonCode.NO_RULE_MATCH));
    }

    private int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }

    private BigDecimal score(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> new BigDecimal("0.0500");
            case REQUEST_3DS -> new BigDecimal("0.5500");
            case REVIEW -> new BigDecimal("0.7500");
            case BLOCK -> new BigDecimal("0.9500");
        };
    }

    private FraudRiskLevel risk(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> FraudRiskLevel.LOW;
            case REQUEST_3DS -> FraudRiskLevel.MEDIUM;
            case REVIEW, BLOCK -> FraudRiskLevel.HIGH;
        };
    }

    private List<List<FraudDecisionAction>> permutations(List<FraudDecisionAction> values) {
        List<List<FraudDecisionAction>> result = new ArrayList<>();
        permute(new ArrayList<>(values), 0, result);
        return result;
    }

    private void permute(List<FraudDecisionAction> values, int index,
            List<List<FraudDecisionAction>> result) {
        if (index == values.size()) {
            result.add(List.copyOf(values));
            return;
        }
        for (int current = index; current < values.size(); current++) {
            Collections.swap(values, index, current);
            permute(values, index + 1, result);
            Collections.swap(values, index, current);
        }
    }
}
