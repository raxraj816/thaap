package com.thaap.payments.paymentattempt;

import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/payment_attempts")
public class PaymentAttemptController {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final AuthorizationRepository authorizationRepository;

    public PaymentAttemptController(PaymentAttemptRepository paymentAttemptRepository,
            AuthorizationRepository authorizationRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.authorizationRepository = authorizationRepository;
    }

    @GetMapping("/{id}")
    public PaymentAttemptResponse getPaymentAttempt(MerchantContext merchantContext, @PathVariable UUID id) {
        return paymentAttemptRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .map(PaymentAttemptResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentAttempt was not found"));
    }

    @GetMapping("/{id}/authorization")
    public PaymentAttemptAuthorizationResponse getPaymentAttemptAuthorization(MerchantContext merchantContext,
            @PathVariable UUID id) {
        return authorizationRepository.findByPaymentAttemptIdAndMerchantId(id, merchantContext.merchantId())
                .map(PaymentAttemptAuthorizationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization was not found"));
    }

    public record PaymentAttemptAuthorizationResponse(UUID paymentAttemptId, String processor,
            String processorReferenceId, String processorResponseCode, NetworkStatus networkStatus,
            long requestedAmount, long authorizedAmount, String currency, AuthorizationStatus status,
            VerificationResult cvcCheck, VerificationResult avsLine1Check, VerificationResult avsPostalCodeCheck,
            ThreeDsResult threeDsResult, Instant createdAt) {
        static PaymentAttemptAuthorizationResponse from(Authorization authorization) {
            return new PaymentAttemptAuthorizationResponse(authorization.getPaymentAttemptId(),
                    authorization.getProcessor(), authorization.getProcessorReferenceId(),
                    authorization.getProcessorResponseCode(), authorization.getNetworkStatus(),
                    authorization.getRequestedAmount(), authorization.getAuthorizedAmount(), authorization.getCurrency(),
                    authorization.getStatus(), authorization.getCvcCheck(), authorization.getAvsLine1Check(),
                    authorization.getAvsPostalCodeCheck(), authorization.getThreeDsResult(),
                    authorization.getCreatedAt());
        }
    }
}
