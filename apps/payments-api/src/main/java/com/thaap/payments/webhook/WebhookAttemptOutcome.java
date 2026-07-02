package com.thaap.payments.webhook;

public enum WebhookAttemptOutcome {
    IN_PROGRESS,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    OUTCOME_UNKNOWN
}
