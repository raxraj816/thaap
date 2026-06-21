package com.thaap.payments.fraud;

import com.thaap.payments.payment.CaptureMethod;
import java.util.UUID;

public record ConfirmDecisionRequest(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
        UUID paymentMethodId, long amount, String currency, CaptureMethod captureMethod, int attemptNumber,
        String cardBin, String cardLast4, String cardCountry, String cardFunding) {
}
