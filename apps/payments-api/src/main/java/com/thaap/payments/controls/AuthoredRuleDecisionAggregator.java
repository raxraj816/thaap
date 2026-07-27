package com.thaap.payments.controls;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AuthoredRuleDecisionAggregator {

    public ConfirmDecision aggregate(ConfirmDecision current, AuthoredRuleAssessment assessment) {
        FraudDecisionAction candidate = assessment.strongestEffectiveAction();
        if (candidate == null) {
            return current;
        }
        FraudDecisionAction action = strength(current.action()) >= strength(candidate) ? current.action() : candidate;
        List<FraudDecisionReasonCode> reasons = new ArrayList<>(current.reasonCodes());
        if (!reasons.contains(FraudDecisionReasonCode.AUTHORED_RULE_MATCH)) {
            reasons.add(FraudDecisionReasonCode.AUTHORED_RULE_MATCH);
        }
        return new ConfirmDecision(action, score(action), risk(action), current.rulesetVersion(),
                current.modelVersion(), reasons, current.ruleEvaluations());
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
}
