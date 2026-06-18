package com.thaap.payments.processor;

import java.util.UUID;

public record ProcessorAuthorizationRequest(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
        UUID paymentMethodId, long amount, String currency, String cardBin, String cardLast4) {
}
