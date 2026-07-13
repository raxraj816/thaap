package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.rules.FraudRule;
import com.thaap.payments.rules.FraudRuleRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicConfirmDecisionEngineTests {

    private FraudRuleRepository repository;
    private DeterministicConfirmDecisionEngine engine;

    @BeforeEach
    void setUp() {
        repository = mock(FraudRuleRepository.class);
        engine = new DeterministicConfirmDecisionEngine(repository);
        when(repository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc("rules_v1", "ACTIVE"))
                .thenReturn(rules());
    }

    @Test
    void amountAndAttemptBoundariesProduceExactActionsScoresAndCompleteResults() {
        assertDecision(99_999L, "USD", 1L, FraudDecisionAction.ALLOW, "0.0500", FraudRiskLevel.LOW,
                FraudDecisionReasonCode.NO_RULE_MATCH, 0);
        assertDecision(100_000L, "USD", 1L, FraudDecisionAction.REQUEST_3DS, "0.5500", FraudRiskLevel.MEDIUM,
                FraudDecisionReasonCode.ELEVATED_AMOUNT, 1);
        assertDecision(4_999_999L, "USD", 1L, FraudDecisionAction.REQUEST_3DS, "0.5500", FraudRiskLevel.MEDIUM,
                FraudDecisionReasonCode.ELEVATED_AMOUNT, 1);
        assertDecision(5_000_000L, "USD", 1L, FraudDecisionAction.BLOCK, "0.9500", FraudRiskLevel.HIGH,
                FraudDecisionReasonCode.EXTREME_AMOUNT, 2);
        assertDecision(5_000_000L, "EUR", 1L, FraudDecisionAction.ALLOW, "0.0500", FraudRiskLevel.LOW,
                FraudDecisionReasonCode.NO_RULE_MATCH, 0);
        assertDecision(99_999L, "USD", 2L, FraudDecisionAction.ALLOW, "0.0500", FraudRiskLevel.LOW,
                FraudDecisionReasonCode.NO_RULE_MATCH, 0);
        assertDecision(99_999L, "USD", 3L, FraudDecisionAction.REVIEW, "0.7500", FraudRiskLevel.HIGH,
                FraudDecisionReasonCode.REPEATED_PAYMENT_ATTEMPT, 1);
        assertDecision(100_000L, "USD", 3L, FraudDecisionAction.REVIEW, "0.7500", FraudRiskLevel.HIGH,
                FraudDecisionReasonCode.REPEATED_PAYMENT_ATTEMPT, 2);
        assertDecision(5_000_000L, "USD", 3L, FraudDecisionAction.BLOCK, "0.9500", FraudRiskLevel.HIGH,
                FraudDecisionReasonCode.EXTREME_AMOUNT, 3);
    }

    @Test
    void everyMatchVectorAndInputPermutationHasStablePrecedenceAndReasonOrdering() {
        List<RuleEvaluation> base = evaluations(false, false, false);
        for (int mask = 0; mask < 8; mask++) {
            boolean block = (mask & 1) != 0;
            boolean review = (mask & 2) != 0;
            boolean stepUp = (mask & 4) != 0;
            List<RuleEvaluation> vector = evaluations(block, review, stepUp);
            ConfirmDecision forward = engine.aggregate(vector);
            ConfirmDecision reverse = engine.aggregate(vector.reversed());
            FraudDecisionAction expected = block ? FraudDecisionAction.BLOCK
                    : review ? FraudDecisionAction.REVIEW
                    : stepUp ? FraudDecisionAction.REQUEST_3DS : FraudDecisionAction.ALLOW;
            assertThat(forward.action()).isEqualTo(expected);
            assertThat(reverse.action()).isEqualTo(expected);
            assertThat(reverse.reasonCodes()).isEqualTo(forward.reasonCodes());
            assertThat(forward.ruleEvaluations()).hasSize(base.size());
        }
    }

    private void assertDecision(long amount, String currency, long attemptNumber, FraudDecisionAction action,
            String score, FraudRiskLevel riskLevel, FraudDecisionReasonCode primaryReason, int matchCount) {
        ConfirmDecision decision = engine.decide(context(amount, currency, attemptNumber));
        assertThat(decision.action()).isEqualTo(action);
        assertThat(decision.riskScore()).isEqualByComparingTo(new BigDecimal(score));
        assertThat(decision.riskLevel()).isEqualTo(riskLevel);
        assertThat(decision.rulesetVersion()).isEqualTo("rules_v1");
        assertThat(decision.modelVersion()).isNull();
        assertThat(decision.reasonCodes().getFirst()).isEqualTo(primaryReason);
        assertThat(decision.ruleEvaluations()).hasSize(3);
        assertThat(decision.ruleEvaluations()).extracting(RuleEvaluation::priority).containsExactly(100, 200, 300);
        assertThat(decision.ruleEvaluations()).filteredOn(RuleEvaluation::matched).hasSize(matchCount);
        assertThat(decision.ruleEvaluations()).allSatisfy(result ->
                assertThat(result.outcome()).isEqualTo(result.matched()
                        ? RuleEvaluationOutcome.MATCH : RuleEvaluationOutcome.NO_MATCH));
    }

    private DecisionFeatureContext context(long amount, String currency, long attemptNumber) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("card.country", "US");
        features.put("card.funding", "CREDIT");
        features.put("payment.amount_minor", amount);
        features.put("payment.attempt_number", attemptNumber);
        features.put("payment.capture_method", "MANUAL");
        features.put("payment.currency", currency);
        features.put("traffic.origin", "MERCHANT_API");
        return new DecisionFeatureContext(features);
    }

    private List<RuleEvaluation> evaluations(boolean block, boolean review, boolean stepUp) {
        List<Boolean> matches = List.of(block, review, stepUp);
        List<FraudRule> definitions = rules();
        return IntStream.range(0, 3).mapToObj(index -> {
            FraudRule rule = definitions.get(index);
            boolean matched = matches.get(index);
            return new RuleEvaluation(rule.getId(), rule.getRuleCode(), rule.getRulesetVersion(), rule.getPriority(),
                    matched, rule.getAction(), rule.getReasonCode(),
                    matched ? RuleEvaluationOutcome.MATCH : RuleEvaluationOutcome.NO_MATCH, 0L);
        }).toList();
    }

    private List<FraudRule> rules() {
        return List.of(
                rule("00000000-0000-0000-0000-000000000001", "BLOCK_USD_EXTREME_AMOUNT_V1", 100,
                        FraudDecisionAction.BLOCK, FraudDecisionReasonCode.EXTREME_AMOUNT,
                        RuleConditionType.CURRENCY_AMOUNT_GTE, "payment.amount_minor", "payment.currency", "USD",
                        5_000_000L),
                rule("00000000-0000-0000-0000-000000000002", "REVIEW_REPEATED_ATTEMPT_V1", 200,
                        FraudDecisionAction.REVIEW, FraudDecisionReasonCode.REPEATED_PAYMENT_ATTEMPT,
                        RuleConditionType.LONG_GTE, "payment.attempt_number", null, null, 3L),
                rule("00000000-0000-0000-0000-000000000003", "REQUEST_3DS_USD_ELEVATED_AMOUNT_V1", 300,
                        FraudDecisionAction.REQUEST_3DS, FraudDecisionReasonCode.ELEVATED_AMOUNT,
                        RuleConditionType.CURRENCY_AMOUNT_GTE, "payment.amount_minor", "payment.currency", "USD",
                        100_000L));
    }

    private FraudRule rule(String id, String code, int priority, FraudDecisionAction action,
            FraudDecisionReasonCode reason, RuleConditionType conditionType, String featureCode,
            String currencyFeatureCode, String currencyValue, long threshold) {
        return new FraudRule(UUID.fromString(id), code, "rules_v1", priority, action, reason, conditionType,
                featureCode, currencyFeatureCode, currencyValue, threshold, "ACTIVE");
    }
}
