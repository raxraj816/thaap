package com.thaap.payments.velocity;

import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VelocityDecisionAggregator {

    public ConfirmDecision aggregate(ConfirmDecision baseDecision, VelocityAssessment assessment) {
        boolean unavailable = assessment.hasUnavailableSignal();
        boolean threshold = assessment.hasThresholdMatch();
        if (!unavailable && !threshold) {
            return baseDecision;
        }
        List<FraudDecisionReasonCode> reasons = new ArrayList<>(baseDecision.reasonCodes());
        if (threshold && !reasons.contains(FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH)) {
            reasons.add(FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH);
        }
        if (unavailable && !reasons.contains(FraudDecisionReasonCode.VELOCITY_UNAVAILABLE)) {
            reasons.add(FraudDecisionReasonCode.VELOCITY_UNAVAILABLE);
        }
        FraudDecisionAction action = stronger(baseDecision.action(), FraudDecisionAction.REVIEW);
        return new ConfirmDecision(action, scoreFor(action), riskFor(action), baseDecision.rulesetVersion(),
                baseDecision.modelVersion(), reasons, baseDecision.ruleEvaluations());
    }

    private FraudDecisionAction stronger(FraudDecisionAction left, FraudDecisionAction right) {
        return strength(left) >= strength(right) ? left : right;
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
