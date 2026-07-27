package com.thaap.payments.simulation;

import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.riskpolicy.PolicyThresholdOperator;
import java.util.List;
import java.util.UUID;

public record RiskSimulationCandidateSnapshot(
        String schemaVersion,
        RiskSimulationCandidateType candidateType,
        UUID candidateVersionId,
        String policyCode,
        int policyVersion,
        List<Threshold> thresholds) {

    public static final String SCHEMA_VERSION = "merchant_risk_policy_v1";

    public RiskSimulationCandidateSnapshot {
        thresholds = List.copyOf(thresholds);
    }

    public record Threshold(
            String thresholdCode,
            String featureCode,
            String featureVersion,
            FeatureValueType valueType,
            PolicyThresholdOperator operator,
            Long longOperand,
            Boolean booleanOperand,
            List<String> stringOperands,
            FraudDecisionAction candidateAction,
            int priority,
            String currencyScope) {

        public Threshold {
            stringOperands = stringOperands == null ? List.of() : List.copyOf(stringOperands);
        }
    }
}
