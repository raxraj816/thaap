package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.rules.FraudRule;
import com.thaap.payments.rules.FraudRuleRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleCatalogueValidatorTests {

    @Test
    void exactCataloguePassesAndMissingDuplicateOrIncompatibleDefinitionsFailReadiness() {
        FraudRuleRepository repository = mock(FraudRuleRepository.class);
        RuleCatalogueValidator validator = new RuleCatalogueValidator(repository);
        List<FraudRule> valid = validRules();
        when(repository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc("rules_v1", "ACTIVE"))
                .thenReturn(valid);
        validator.validate();

        when(repository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc("rules_v1", "ACTIVE"))
                .thenReturn(valid.subList(0, 2));
        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact required catalogue");

        when(repository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc("rules_v1", "ACTIVE"))
                .thenReturn(List.of(valid.getFirst(), valid.getFirst(), valid.getLast()));
        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);

        FraudRule wrongPriority = rule("BLOCK_USD_EXTREME_AMOUNT_V1", 999, FraudDecisionAction.BLOCK,
                FraudDecisionReasonCode.EXTREME_AMOUNT, RuleConditionType.CURRENCY_AMOUNT_GTE,
                "payment.amount_minor", "payment.currency", "USD", 5_000_000L);
        when(repository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc("rules_v1", "ACTIVE"))
                .thenReturn(List.of(wrongPriority, valid.get(1), valid.get(2)));
        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible rule metadata");

        FraudRule wrongFeature = rule("REVIEW_REPEATED_ATTEMPT_V1", 200, FraudDecisionAction.REVIEW,
                FraudDecisionReasonCode.REPEATED_PAYMENT_ATTEMPT, RuleConditionType.LONG_GTE,
                "payment.currency", null, null, 3L);
        when(repository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc("rules_v1", "ACTIVE"))
                .thenReturn(List.of(valid.getFirst(), wrongFeature, valid.getLast()));
        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);
    }

    private List<FraudRule> validRules() {
        return List.of(
                rule("BLOCK_USD_EXTREME_AMOUNT_V1", 100, FraudDecisionAction.BLOCK,
                        FraudDecisionReasonCode.EXTREME_AMOUNT, RuleConditionType.CURRENCY_AMOUNT_GTE,
                        "payment.amount_minor", "payment.currency", "USD", 5_000_000L),
                rule("REVIEW_REPEATED_ATTEMPT_V1", 200, FraudDecisionAction.REVIEW,
                        FraudDecisionReasonCode.REPEATED_PAYMENT_ATTEMPT, RuleConditionType.LONG_GTE,
                        "payment.attempt_number", null, null, 3L),
                rule("REQUEST_3DS_USD_ELEVATED_AMOUNT_V1", 300, FraudDecisionAction.REQUEST_3DS,
                        FraudDecisionReasonCode.ELEVATED_AMOUNT, RuleConditionType.CURRENCY_AMOUNT_GTE,
                        "payment.amount_minor", "payment.currency", "USD", 100_000L));
    }

    private FraudRule rule(String code, int priority, FraudDecisionAction action, FraudDecisionReasonCode reason,
            RuleConditionType conditionType, String featureCode, String currencyFeatureCode, String currencyValue,
            long threshold) {
        return new FraudRule(UUID.randomUUID(), code, "rules_v1", priority, action, reason, conditionType,
                featureCode, currencyFeatureCode, currencyValue, threshold, "ACTIVE");
    }
}
