package com.thaap.payments.fraud;

import java.util.UUID;

public record RuleEvaluation(UUID ruleId, String ruleCode, String ruleVersion, int priority,
        boolean matched, FraudDecisionAction action, FraudDecisionReasonCode reasonCode,
        RuleEvaluationOutcome outcome, long latencyUs) {
}
