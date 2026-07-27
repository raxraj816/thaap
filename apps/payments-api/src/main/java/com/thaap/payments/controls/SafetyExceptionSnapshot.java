package com.thaap.payments.controls;

import com.thaap.payments.fraud.FraudDecisionAction;
import java.time.Instant;
import java.util.UUID;

public record SafetyExceptionSnapshot(String schemaVersion, UUID exceptionVersionId,
        UUID sourceRuleVersionId, String sourceRuleJson, String sourceRuleDigest,
        FraudDecisionAction sourceAction, String subjectType, String subjectIdentityHmac,
        String subjectHmacKeyVersion, String reasonCode, Instant validFrom, Instant expiresAt) {

    public static final String SCHEMA_VERSION = "risk_safety_exception_v1";
}
