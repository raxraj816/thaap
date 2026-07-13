package com.thaap.payments.fraud;

import com.thaap.payments.rules.FraudRule;
import com.thaap.payments.rules.FraudRuleRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class RuleCatalogueValidator implements ApplicationRunner {

    private static final Set<String> REQUIRED_CODES = Set.of(
            "BLOCK_USD_EXTREME_AMOUNT_V1",
            "REVIEW_REPEATED_ATTEMPT_V1",
            "REQUEST_3DS_USD_ELEVATED_AMOUNT_V1");
    private static final Map<String, RuleSpec> REQUIRED_SPECS = Map.of(
            "BLOCK_USD_EXTREME_AMOUNT_V1", new RuleSpec(100, FraudDecisionAction.BLOCK,
                    FraudDecisionReasonCode.EXTREME_AMOUNT, RuleConditionType.CURRENCY_AMOUNT_GTE,
                    "payment.amount_minor", "payment.currency", "USD", 5_000_000L),
            "REVIEW_REPEATED_ATTEMPT_V1", new RuleSpec(200, FraudDecisionAction.REVIEW,
                    FraudDecisionReasonCode.REPEATED_PAYMENT_ATTEMPT, RuleConditionType.LONG_GTE,
                    "payment.attempt_number", null, null, 3L),
            "REQUEST_3DS_USD_ELEVATED_AMOUNT_V1", new RuleSpec(300, FraudDecisionAction.REQUEST_3DS,
                    FraudDecisionReasonCode.ELEVATED_AMOUNT, RuleConditionType.CURRENCY_AMOUNT_GTE,
                    "payment.amount_minor", "payment.currency", "USD", 100_000L));

    private final FraudRuleRepository fraudRuleRepository;

    public RuleCatalogueValidator(FraudRuleRepository fraudRuleRepository) {
        this.fraudRuleRepository = fraudRuleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    public void validate() {
        List<FraudRule> rules = fraudRuleRepository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc(
                DeterministicConfirmDecisionEngine.RULESET_VERSION, "ACTIVE");
        if (rules.size() != REQUIRED_CODES.size()
                || !rules.stream().map(FraudRule::getRuleCode).collect(java.util.stream.Collectors.toSet())
                        .equals(REQUIRED_CODES)) {
            throw new IllegalStateException("rules_v1 must contain the exact required catalogue");
        }
        for (FraudRule rule : rules) {
            validateRule(rule);
        }
    }

    private void validateRule(FraudRule rule) {
        RuleSpec expected = REQUIRED_SPECS.get(rule.getRuleCode());
        if (!DeterministicConfirmDecisionEngine.RULESET_VERSION.equals(rule.getRulesetVersion())
                || !"ACTIVE".equals(rule.getStatus())
                || expected == null
                || rule.getPriority() != expected.priority()
                || rule.getAction() != expected.action()
                || rule.getReasonCode() != expected.reasonCode()
                || rule.getConditionType() != expected.conditionType()
                || !java.util.Objects.equals(rule.getFeatureCode(), expected.featureCode())
                || !java.util.Objects.equals(rule.getCurrencyFeatureCode(), expected.currencyFeatureCode())
                || !java.util.Objects.equals(rule.getCurrencyValue(), expected.currencyValue())
                || rule.getLongThreshold() != expected.longThreshold()) {
            throw new IllegalStateException("rules_v1 contains incompatible rule metadata");
        }
        FeatureValueType featureType = FeatureCatalogue.requiredType(rule.getFeatureCode());
        if (featureType != FeatureValueType.LONG) {
            throw new IllegalStateException("rules_v1 numeric feature must be a governed LONG");
        }
        if (rule.getConditionType() == RuleConditionType.CURRENCY_AMOUNT_GTE) {
            if (FeatureCatalogue.requiredType(rule.getCurrencyFeatureCode()) != FeatureValueType.STRING
                    || !"USD".equals(rule.getCurrencyValue())) {
                throw new IllegalStateException("rules_v1 currency predicate is incompatible");
            }
        } else if (rule.getCurrencyFeatureCode() != null || rule.getCurrencyValue() != null) {
            throw new IllegalStateException("rules_v1 LONG predicate must not include currency configuration");
        }
    }

    private record RuleSpec(int priority, FraudDecisionAction action, FraudDecisionReasonCode reasonCode,
            RuleConditionType conditionType, String featureCode, String currencyFeatureCode,
            String currencyValue, long longThreshold) {
    }
}
