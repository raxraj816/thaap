package com.thaap.payments.fraudlist;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FraudListDecisionAggregator {

    public ConfirmDecision aggregate(ConfirmDecision current, FraudListAssessment assessment) {
        FraudDecisionAction candidate = assessment.strongestCandidate();
        if (candidate == null) {
            return current;
        }
        List<FraudDecisionReasonCode> reasons = new ArrayList<>(current.reasonCodes());
        if (!reasons.contains(FraudDecisionReasonCode.FRAUD_LIST_MATCH)) {
            reasons.add(FraudDecisionReasonCode.FRAUD_LIST_MATCH);
        }
        FraudDecisionAction action = strength(current.action()) >= strength(candidate) ? current.action() : candidate;
        return new ConfirmDecision(action, scoreFor(action), riskFor(action), current.rulesetVersion(),
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
