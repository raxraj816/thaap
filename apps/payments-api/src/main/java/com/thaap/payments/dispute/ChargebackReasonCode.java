package com.thaap.payments.dispute;

import com.thaap.payments.review.FraudType;

public enum ChargebackReasonCode {
    FRAUDULENT_STOLEN_CREDENTIAL(FraudType.STOLEN_PAYMENT_CREDENTIAL),
    FRAUDULENT_ACCOUNT_TAKEOVER(FraudType.ACCOUNT_TAKEOVER),
    FRAUDULENT_CARD_TESTING(FraudType.CARD_TESTING),
    FRAUDULENT_FIRST_PARTY_MISUSE(FraudType.FIRST_PARTY_MISUSE),
    PRODUCT_NOT_RECEIVED(null),
    DUPLICATE(null),
    CREDIT_NOT_PROCESSED(null),
    GENERAL(null);

    private final FraudType fraudType;

    ChargebackReasonCode(FraudType fraudType) {
        this.fraudType = fraudType;
    }

    public boolean isFraud() {
        return fraudType != null;
    }

    public FraudType fraudType() {
        return fraudType;
    }
}
