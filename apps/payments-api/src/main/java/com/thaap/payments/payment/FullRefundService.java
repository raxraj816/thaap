package com.thaap.payments.payment;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FullRefundService {
    private final AuthorizationRepository authorizationRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final WebhookDeliveryService webhookDeliveryService;

    public FullRefundService(AuthorizationRepository authorizationRepository,
            RefundRepository refundRepository, LedgerService ledgerService,
            WebhookDeliveryService webhookDeliveryService) {
        this.authorizationRepository = authorizationRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    public Result refundLocked(UUID merchantId, PaymentIntent paymentIntent,
            RefundReasonCode reasonCode, UUID sourceReviewResolutionId, boolean allowExactReuse) {
        Refund existing = refundRepository.findByPaymentIntentIdAndMerchantId(
                paymentIntent.getId(), merchantId).orElse(null);
        if (existing != null) {
            if (allowExactReuse && paymentIntent.getStatus() == PaymentIntentStatus.REFUNDED
                    && existing.getAmount() == paymentIntent.getAmount()
                    && existing.getCurrency().equals(paymentIntent.getCurrency())) {
                return new Result(existing, null, null, true);
            }
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_REFUND_EXISTS, "PaymentIntent already has a recorded Refund");
        }
        if (paymentIntent.getStatus() != PaymentIntentStatus.CAPTURED) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_REFUNDABLE, "PaymentIntent cannot be refunded from status " + paymentIntent.getStatus());
        }
        Authorization authorization = latestApprovedAuthorization(merchantId, paymentIntent.getId());
        requireAuthorizationMatchesPaymentIntent(authorization, paymentIntent);
        Refund refund = refundRepository.saveAndFlush(new Refund(merchantId, paymentIntent.getId(),
                authorization.getPaymentAttemptId(), authorization.getId(), paymentIntent.getAmount(),
                paymentIntent.getCurrency(), reasonCode, sourceReviewResolutionId));
        LedgerService.RefundLedgerResult ledgerResult = ledgerService.recordRefund(merchantId,
                paymentIntent.getId(), refund.getId(), refund.getAmount(), refund.getCurrency());
        paymentIntent.refund(refund.getAmount());
        webhookDeliveryService.createDeliveriesInCurrentTransaction(merchantId, "refund.created",
                refund.getId(), paymentIntent.getTrafficOrigin());
        return new Result(refund, authorization, ledgerResult, false);
    }

    private Authorization latestApprovedAuthorization(UUID merchantId, UUID paymentIntentId) {
        return authorizationRepository.findApprovedAuthorizationsForPaymentIntent(merchantId,
                        paymentIntentId, PaymentAttemptStatus.AUTHORIZED, AuthorizationStatus.APPROVED)
                .stream().findFirst()
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_REFUND_AUTHORIZATION_MISSING, "PaymentIntent does not have approved authorization evidence for Refund"));
    }

    private void requireAuthorizationMatchesPaymentIntent(Authorization authorization,
            PaymentIntent paymentIntent) {
        if (!authorization.getCurrency().equals(paymentIntent.getCurrency())
                || authorization.getAuthorizedAmount() != paymentIntent.getAmount()) {
            throw new PublicApiException(PublicApiErrorDescriptor.AUTHORIZATION_PAYMENT_MISMATCH, "Approved authorization does not match PaymentIntent");
        }
    }

    public record Result(Refund refund, Authorization authorization,
            LedgerService.RefundLedgerResult ledgerResult, boolean reused) {}
}
