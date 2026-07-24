package com.thaap.payments.paymentattempt;

import java.util.UUID;
import com.thaap.payments.authentication.AuthenticationNextAction;

public record PaymentAttemptResponse(UUID id, UUID merchantId, UUID paymentIntentId, UUID paymentMethodId,
        PaymentAttemptStatus status, int attemptNumber, UUID fraudDecisionId, String processorAuthorizationId,
        ThreeDsStatus threeDsStatus, com.thaap.payments.tenant.TrafficOrigin trafficOrigin,
        AuthenticationNextAction nextAction) {

    public static PaymentAttemptResponse from(PaymentAttempt paymentAttempt) {
        return new PaymentAttemptResponse(paymentAttempt.getId(), paymentAttempt.getMerchantId(),
                paymentAttempt.getPaymentIntentId(), paymentAttempt.getPaymentMethodId(), paymentAttempt.getStatus(),
                paymentAttempt.getAttemptNumber(), paymentAttempt.getFraudDecisionId(),
                paymentAttempt.getProcessorAuthorizationId(), paymentAttempt.getThreeDsStatus(),
                paymentAttempt.getTrafficOrigin(), null);
    }

    public static PaymentAttemptResponse from(PaymentAttempt paymentAttempt,
            AuthenticationNextAction nextAction) {
        return new PaymentAttemptResponse(paymentAttempt.getId(), paymentAttempt.getMerchantId(),
                paymentAttempt.getPaymentIntentId(), paymentAttempt.getPaymentMethodId(), paymentAttempt.getStatus(),
                paymentAttempt.getAttemptNumber(), paymentAttempt.getFraudDecisionId(),
                paymentAttempt.getProcessorAuthorizationId(), paymentAttempt.getThreeDsStatus(),
                paymentAttempt.getTrafficOrigin(), nextAction);
    }
}
