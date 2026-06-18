package com.thaap.payments.processor;

import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;

public record ProcessorAuthorizationResult(String processor, String processorReferenceId, String responseCode,
        NetworkStatus networkStatus, long requestedAmount, long authorizedAmount, String currency,
        AuthorizationStatus status, VerificationResult cvcCheck, VerificationResult avsLine1Check,
        VerificationResult avsPostalCodeCheck, ThreeDsResult threeDsResult, String simulatorOutcomeCode) {
}
