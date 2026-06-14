package com.thaap.payments.paymentattempt;

import java.util.UUID;

public record PaymentAttemptResponse(UUID id, UUID merchantId, UUID paymentIntentId, UUID paymentMethodId,
        PaymentAttemptStatus status, int attemptNumber, UUID fraudDecisionId, String processorAuthorizationId,
        ThreeDsStatus threeDsStatus) {

    public static PaymentAttemptResponse from(PaymentAttempt paymentAttempt) {
        return new PaymentAttemptResponse(paymentAttempt.getId(), paymentAttempt.getMerchantId(),
                paymentAttempt.getPaymentIntentId(), paymentAttempt.getPaymentMethodId(), paymentAttempt.getStatus(),
                paymentAttempt.getAttemptNumber(), paymentAttempt.getFraudDecisionId(),
                paymentAttempt.getProcessorAuthorizationId(), paymentAttempt.getThreeDsStatus());
    }
}
