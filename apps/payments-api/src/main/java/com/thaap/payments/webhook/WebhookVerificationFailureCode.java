package com.thaap.payments.webhook;

public enum WebhookVerificationFailureCode {
    TARGET_REJECTED,
    TRANSPORT_FAILED,
    NON_SUCCESS_STATUS,
    MISSING_RESPONSE_FIELD,
    TOKEN_MISMATCH,
    ENDPOINT_CHANGED,
    ENDPOINT_DISABLED,
    EXPIRED,
    RECOVERY_REQUIRED
}
