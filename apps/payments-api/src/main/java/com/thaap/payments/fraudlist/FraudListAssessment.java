package com.thaap.payments.fraudlist;

import com.thaap.payments.fraud.FraudDecisionAction;
import java.util.Comparator;
import java.util.List;

public record FraudListAssessment(List<FraudListEvaluation> evaluations) {

    public FraudListAssessment {
        evaluations = List.copyOf(evaluations);
    }

    public static FraudListAssessment empty() {
        return new FraudListAssessment(List.of());
    }

    public boolean hasMatch() {
        return evaluations.stream().anyMatch(FraudListEvaluation::matched);
    }

    public FraudDecisionAction strongestCandidate() {
        return evaluations.stream().filter(FraudListEvaluation::matched)
                .map(FraudListEvaluation::candidateAction)
                .max(Comparator.comparingInt(FraudListAssessment::strength))
                .orElse(null);
    }

    private static int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }
}
