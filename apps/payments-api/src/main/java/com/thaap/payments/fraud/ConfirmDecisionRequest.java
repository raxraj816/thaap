package com.thaap.payments.fraud;

import java.util.UUID;

public record ConfirmDecisionRequest(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
        UUID paymentMethodId, long amount, String currency) {
}
