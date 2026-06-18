package com.thaap.payments.paymentattempt;

public enum PaymentAttemptStatus {
    CREATED,
    READY_FOR_PROCESSOR,
    AUTHORIZED,
    DECLINED,
    REQUIRES_ACTION
}
