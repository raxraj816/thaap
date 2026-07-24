package com.thaap.payments.riskpolicy;

import com.thaap.payments.fraud.FraudDecisionAction;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record MerchantPolicyAssessment(
        MerchantRiskConfiguration configuration,
        List<MerchantPolicyEvaluation> evaluations) {

    public MerchantPolicyAssessment {
        Objects.requireNonNull(configuration, "configuration");
        evaluations = List.copyOf(evaluations);
    }

    public FraudDecisionAction strongestCandidate() {
        if (configuration.status() == MerchantRiskConfigurationStatus.UNAVAILABLE) {
            return FraudDecisionAction.REVIEW;
        }
        return evaluations.stream()
                .filter(MerchantPolicyEvaluation::matched)
                .map(MerchantPolicyEvaluation::threshold)
                .sorted(Comparator.comparingInt((MerchantPolicyThreshold threshold) ->
                                strength(threshold.candidateAction())).reversed()
                        .thenComparing(Comparator.comparingInt(MerchantPolicyThreshold::priority).reversed())
                        .thenComparing(MerchantPolicyThreshold::code))
                .map(MerchantPolicyThreshold::candidateAction)
                .findFirst().orElse(null);
    }

    public boolean hasMatch() {
        return evaluations.stream().anyMatch(MerchantPolicyEvaluation::matched);
    }

    private int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }
}
