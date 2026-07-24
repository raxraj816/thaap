package com.thaap.payments.review;

public enum ReviewResolutionOutcome {
    APPROVE,
    CANCEL,
    CANCEL_AND_REPORT_FRAUD,
    NO_ACTION,
    REFUND,
    REFUND_AND_REPORT_FRAUD
}
