package com.thaap.payments.fraud;

import java.math.BigDecimal;
import java.util.List;

public record ConfirmDecision(FraudDecisionAction action, BigDecimal riskScore, FraudRiskLevel riskLevel,
        String rulesetVersion, String modelVersion, List<FraudDecisionReasonCode> reasonCodes,
        List<RuleEvaluation> ruleEvaluations) {

    public ConfirmDecision(FraudDecisionAction action, BigDecimal riskScore, FraudRiskLevel riskLevel,
            String rulesetVersion, String modelVersion, List<FraudDecisionReasonCode> reasonCodes) {
        this(action, riskScore, riskLevel, rulesetVersion, modelVersion, reasonCodes, List.of());
    }

    public ConfirmDecision {
        reasonCodes = List.copyOf(reasonCodes);
        ruleEvaluations = List.copyOf(ruleEvaluations);
    }
}
