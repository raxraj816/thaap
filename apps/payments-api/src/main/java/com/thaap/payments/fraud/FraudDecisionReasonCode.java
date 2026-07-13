package com.thaap.payments.fraud;

public enum FraudDecisionReasonCode {
    RULES_NOT_ENABLED,
    EXTREME_AMOUNT,
    REPEATED_PAYMENT_ATTEMPT,
    ELEVATED_AMOUNT,
    NO_RULE_MATCH
}
