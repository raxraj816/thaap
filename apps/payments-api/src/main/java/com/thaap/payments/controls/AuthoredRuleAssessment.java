package com.thaap.payments.controls;

import com.thaap.payments.fraud.FraudDecisionAction;
import java.util.List;
import java.util.UUID;

public record AuthoredRuleAssessment(List<Evaluation> evaluations,
        FraudDecisionAction strongestEffectiveAction) {

    public AuthoredRuleAssessment {
        evaluations = List.copyOf(evaluations);
    }

    public record Evaluation(UUID ruleVersionId, String ruleDigest, boolean matched,
            FraudDecisionAction candidateAction, String exceptionDisposition,
            UUID appliedExceptionVersionId, FraudDecisionAction effectiveAction) { }
}
