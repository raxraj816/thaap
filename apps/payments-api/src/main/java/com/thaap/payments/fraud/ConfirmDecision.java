package com.thaap.payments.fraud;

import java.math.BigDecimal;
import java.util.List;

public record ConfirmDecision(FraudDecisionAction action, BigDecimal riskScore, FraudRiskLevel riskLevel,
        String rulesetVersion, String modelVersion, List<FraudDecisionReasonCode> reasonCodes) {
}
