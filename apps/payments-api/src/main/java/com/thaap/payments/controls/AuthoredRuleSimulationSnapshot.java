package com.thaap.payments.controls;

import com.thaap.payments.fraud.FraudDecisionAction;
import java.util.UUID;

public record AuthoredRuleSimulationSnapshot(String schemaVersion, UUID ruleVersionId,
        String ruleJson, String ruleDigest, FraudDecisionAction candidateAction) {

    public static final String SCHEMA_VERSION = "authored_rule_v1";
}
