package com.thaap.payments.fraud;

import com.thaap.payments.rules.FraudRule;
import com.thaap.payments.rules.FraudRuleRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DeterministicConfirmDecisionEngine implements ConfirmDecisionEngine {

    public static final String RULESET_VERSION = "rules_v1";
    private static final Map<FraudDecisionAction, Integer> ACTION_STRENGTH = Map.of(
            FraudDecisionAction.ALLOW, 0,
            FraudDecisionAction.REQUEST_3DS, 1,
            FraudDecisionAction.REVIEW, 2,
            FraudDecisionAction.BLOCK, 3);

    private final FraudRuleRepository fraudRuleRepository;

    public DeterministicConfirmDecisionEngine(FraudRuleRepository fraudRuleRepository) {
        this.fraudRuleRepository = fraudRuleRepository;
    }

    @Override
    public ConfirmDecision decide(DecisionFeatureContext context) {
        List<FraudRule> rules = fraudRuleRepository
                .findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc(RULESET_VERSION, "ACTIVE");
        if (rules.size() != 3) {
            throw new IllegalStateException("Active rules_v1 catalogue is incomplete");
        }
        List<RuleEvaluation> evaluations = new ArrayList<>();
        for (FraudRule rule : rules) {
            long startedNanos = System.nanoTime();
            boolean matched = evaluate(rule, context.features());
            long latencyUs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000L);
            evaluations.add(new RuleEvaluation(rule.getId(), rule.getRuleCode(), rule.getRulesetVersion(),
                    rule.getPriority(), matched, rule.getAction(), rule.getReasonCode(),
                    matched ? RuleEvaluationOutcome.MATCH : RuleEvaluationOutcome.NO_MATCH, latencyUs));
        }
        return aggregate(evaluations);
    }

    ConfirmDecision aggregate(List<RuleEvaluation> evaluations) {
        List<RuleEvaluation> immutableEvaluations = List.copyOf(evaluations);
        List<RuleEvaluation> matches = immutableEvaluations.stream().filter(RuleEvaluation::matched).toList();
        if (matches.isEmpty()) {
            return new ConfirmDecision(FraudDecisionAction.ALLOW, new BigDecimal("0.0500"), FraudRiskLevel.LOW,
                    RULESET_VERSION, null, List.of(FraudDecisionReasonCode.NO_RULE_MATCH), immutableEvaluations);
        }
        Comparator<RuleEvaluation> precedence = Comparator
                .comparingInt((RuleEvaluation result) -> ACTION_STRENGTH.get(result.action())).reversed()
                .thenComparingInt(RuleEvaluation::priority)
                .thenComparing(RuleEvaluation::ruleCode);
        List<RuleEvaluation> orderedMatches = matches.stream().sorted(precedence).toList();
        FraudDecisionAction action = orderedMatches.getFirst().action();
        return new ConfirmDecision(action, scoreFor(action), riskFor(action), RULESET_VERSION, null,
                orderedMatches.stream().map(RuleEvaluation::reasonCode).toList(), immutableEvaluations);
    }

    private boolean evaluate(FraudRule rule, Map<String, Object> features) {
        Object rawValue = features.get(rule.getFeatureCode());
        if (!(rawValue instanceof Long longValue)) {
            throw new IllegalStateException("Rule feature is absent or has incompatible type");
        }
        return switch (rule.getConditionType()) {
            case LONG_GTE -> longValue >= rule.getLongThreshold();
            case CURRENCY_AMOUNT_GTE -> {
                Object rawCurrency = features.get(rule.getCurrencyFeatureCode());
                if (!(rawCurrency instanceof String currency)) {
                    throw new IllegalStateException("Rule currency feature is absent or has incompatible type");
                }
                yield currency.equals(rule.getCurrencyValue()) && longValue >= rule.getLongThreshold();
            }
        };
    }

    private BigDecimal scoreFor(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> new BigDecimal("0.0500");
            case REQUEST_3DS -> new BigDecimal("0.5500");
            case REVIEW -> new BigDecimal("0.7500");
            case BLOCK -> new BigDecimal("0.9500");
        };
    }

    private FraudRiskLevel riskFor(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> FraudRiskLevel.LOW;
            case REQUEST_3DS -> FraudRiskLevel.MEDIUM;
            case REVIEW, BLOCK -> FraudRiskLevel.HIGH;
        };
    }
}
