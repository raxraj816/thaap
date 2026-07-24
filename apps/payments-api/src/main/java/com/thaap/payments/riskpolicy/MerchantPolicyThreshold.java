package com.thaap.payments.riskpolicy;

import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MerchantPolicyThreshold(
        UUID id,
        UUID policyVersionId,
        String code,
        UUID featureDefinitionId,
        String featureCode,
        String featureVersion,
        FeatureValueType valueType,
        PolicyThresholdOperator operator,
        Long longOperand,
        Boolean booleanOperand,
        List<String> stringOperands,
        FraudDecisionAction candidateAction,
        int priority,
        PolicyThresholdReasonCode reasonCode,
        String currencyScope) {

    public MerchantPolicyThreshold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(featureDefinitionId, "featureDefinitionId");
        Objects.requireNonNull(featureCode, "featureCode");
        Objects.requireNonNull(featureVersion, "featureVersion");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(operator, "operator");
        stringOperands = stringOperands == null ? List.of() : List.copyOf(stringOperands);
        Objects.requireNonNull(candidateAction, "candidateAction");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (candidateAction == FraudDecisionAction.ALLOW) {
            throw new IllegalArgumentException("Merchant policy cannot produce ALLOW");
        }
    }
}
