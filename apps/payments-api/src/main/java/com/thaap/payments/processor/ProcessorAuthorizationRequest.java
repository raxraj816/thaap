package com.thaap.payments.processor;

import java.util.UUID;

public record ProcessorAuthorizationRequest(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
        UUID paymentMethodId, long amount, String currency, String cardBin, String cardLast4,
        boolean authenticated) {

    public ProcessorAuthorizationRequest(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID paymentMethodId, long amount, String currency, String cardBin, String cardLast4) {
        this(merchantId, paymentIntentId, paymentAttemptId, paymentMethodId,
                amount, currency, cardBin, cardLast4, false);
    }
}
