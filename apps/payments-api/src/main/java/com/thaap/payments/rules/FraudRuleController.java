package com.thaap.payments.rules;

import com.thaap.payments.fraud.DeterministicConfirmDecisionEngine;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.RuleConditionType;
import com.thaap.payments.tenant.MerchantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FraudRuleController {

    private final FraudRuleRepository fraudRuleRepository;

    public FraudRuleController(FraudRuleRepository fraudRuleRepository) {
        this.fraudRuleRepository = fraudRuleRepository;
    }

    @GetMapping("/rules")
    public List<FraudRuleResponse> listRules(MerchantContext merchantContext) {
        return fraudRuleRepository.findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc(
                        DeterministicConfirmDecisionEngine.RULESET_VERSION, "ACTIVE").stream()
                .map(FraudRuleResponse::from)
                .toList();
    }

    public record FraudRuleResponse(UUID id, String ruleCode, String rulesetVersion, int priority,
            FraudDecisionAction action, FraudDecisionReasonCode reasonCode, RuleConditionType conditionType,
            String featureCode, String currencyFeatureCode, String currencyValue, long longThreshold) {
        static FraudRuleResponse from(FraudRule rule) {
            return new FraudRuleResponse(rule.getId(), rule.getRuleCode(), rule.getRulesetVersion(),
                    rule.getPriority(), rule.getAction(), rule.getReasonCode(), rule.getConditionType(),
                    rule.getFeatureCode(), rule.getCurrencyFeatureCode(), rule.getCurrencyValue(),
                    rule.getLongThreshold());
        }
    }
}
