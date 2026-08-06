package com.thaap.payments.paymentattempt;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.AuthorizationVoid;
import com.thaap.payments.authorization.AuthorizationVoidReason;
import com.thaap.payments.authorization.AuthorizationVoidRepository;
import com.thaap.payments.authorization.AuthorizationVoidStatus;
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
    private final AuthorizationVoidRepository authorizationVoidRepository;

    public PaymentAttemptController(PaymentAttemptRepository paymentAttemptRepository,
            AuthorizationRepository authorizationRepository,
            AuthorizationVoidRepository authorizationVoidRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.authorizationRepository = authorizationRepository;
        this.authorizationVoidRepository = authorizationVoidRepository;
    }

    @GetMapping("/{id}")
    public PaymentAttemptResponse getPaymentAttempt(MerchantContext merchantContext, @PathVariable UUID id) {
        return paymentAttemptRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .map(PaymentAttemptResponse::from)
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_ATTEMPT_NOT_FOUND, "PaymentAttempt was not found"));
    }

    @GetMapping("/{id}/authorization")
    public PaymentAttemptAuthorizationResponse getPaymentAttemptAuthorization(MerchantContext merchantContext,
            @PathVariable UUID id) {
        return authorizationRepository.findByPaymentAttemptIdAndMerchantId(id, merchantContext.merchantId())
                .map(authorization -> PaymentAttemptAuthorizationResponse.from(authorization,
                        authorizationVoidRepository.findByAuthorizationIdAndMerchantId(authorization.getId(),
                                merchantContext.merchantId()).orElse(null)))
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.AUTHORIZATION_NOT_FOUND, "Authorization was not found"));
    }

    public record PaymentAttemptAuthorizationResponse(UUID paymentAttemptId, String processor,
            String processorReferenceId, String processorResponseCode, NetworkStatus networkStatus,
            long requestedAmount, long authorizedAmount, String currency, AuthorizationStatus status,
            VerificationResult cvcCheck, VerificationResult avsLine1Check, VerificationResult avsPostalCodeCheck,
            ThreeDsResult threeDsResult, boolean voided, UUID authorizationVoidId,
            AuthorizationVoidStatus authorizationVoidStatus, AuthorizationVoidReason authorizationVoidReason,
            Instant voidedAt, Instant createdAt) {
        static PaymentAttemptAuthorizationResponse from(Authorization authorization,
                AuthorizationVoid authorizationVoid) {
            return new PaymentAttemptAuthorizationResponse(authorization.getPaymentAttemptId(),
                    authorization.getProcessor(), authorization.getProcessorReferenceId(),
                    authorization.getProcessorResponseCode(), authorization.getNetworkStatus(),
                    authorization.getRequestedAmount(), authorization.getAuthorizedAmount(), authorization.getCurrency(),
                    authorization.getStatus(), authorization.getCvcCheck(), authorization.getAvsLine1Check(),
                    authorization.getAvsPostalCodeCheck(), authorization.getThreeDsResult(), authorizationVoid != null,
                    authorizationVoid == null ? null : authorizationVoid.getId(),
                    authorizationVoid == null ? null : authorizationVoid.getStatus(),
                    authorizationVoid == null ? null : authorizationVoid.getReason(),
                    authorizationVoid == null ? null : authorizationVoid.getCreatedAt(),
                    authorization.getCreatedAt());
        }
    }
}
