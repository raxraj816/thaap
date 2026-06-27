package com.thaap.payments.payment;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/refunds")
public class RefundController {

    private final PaymentIntentRepository paymentIntentRepository;
    private final AuthorizationRepository authorizationRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final AuditLogService auditLogService;

    public RefundController(PaymentIntentRepository paymentIntentRepository,
            AuthorizationRepository authorizationRepository, RefundRepository refundRepository,
            LedgerService ledgerService, IdempotencyService idempotencyService,
            WebhookDeliveryService webhookDeliveryService, AuditLogService auditLogService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.authorizationRepository = authorizationRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.auditLogService = auditLogService;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> createRefund(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request, HttpServletRequest httpServletRequest) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "refunds.create", request,
                RefundResponse.class,
                () -> createRefundOnce(merchantContext, request, requestIdFrom(httpServletRequest)));
    }

    @GetMapping("/{id}")
    public RefundResponse getRefund(MerchantContext merchantContext, @PathVariable UUID id) {
        Refund refund = refundRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund was not found"));
        return RefundResponse.from(refund);
    }

    protected ResponseEntity<RefundResponse> createRefundOnce(MerchantContext merchantContext,
            CreateRefundRequest request, String requestId) {
        PaymentIntent paymentIntent = paymentIntentRepository
                .findByIdAndMerchantIdForUpdate(request.paymentIntentId(), merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentIntent was not found"));
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        if (startingStatus != PaymentIntentStatus.CAPTURED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be refunded from status " + startingStatus);
        }
        if (request.amount() != paymentIntent.getAmount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Refund amount must equal captured PaymentIntent amount until partial refunds are supported");
        }
        if (refundRepository.existsByPaymentIntentIdAndMerchantId(paymentIntent.getId(),
                merchantContext.merchantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent already has a recorded Refund");
        }

        Authorization authorization = latestApprovedAuthorization(merchantContext, paymentIntent.getId());
        requireAuthorizationMatchesPaymentIntent(authorization, paymentIntent);
        Refund refund = refundRepository.saveAndFlush(new Refund(merchantContext.merchantId(),
                paymentIntent.getId(), authorization.getPaymentAttemptId(), authorization.getId(), request.amount(),
                paymentIntent.getCurrency()));
        LedgerService.RefundLedgerResult ledgerResult = ledgerService.recordRefund(merchantContext.merchantId(),
                paymentIntent.getId(), refund.getId(), refund.getAmount(), refund.getCurrency());
        paymentIntent.refund(request.amount());
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "refund.created", refund.getId());
        recordRefundAuditLog(merchantContext, requestId, startingStatus, paymentIntent, authorization, refund,
                ledgerResult);
        return ResponseEntity.status(HttpStatus.CREATED).body(RefundResponse.from(refund));
    }

    private Authorization latestApprovedAuthorization(MerchantContext merchantContext, UUID paymentIntentId) {
        return authorizationRepository.findApprovedAuthorizationsForPaymentIntent(merchantContext.merchantId(),
                        paymentIntentId, PaymentAttemptStatus.AUTHORIZED, AuthorizationStatus.APPROVED)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "PaymentIntent does not have approved authorization evidence for Refund"));
    }

    private void requireAuthorizationMatchesPaymentIntent(Authorization authorization, PaymentIntent paymentIntent) {
        if (!authorization.getCurrency().equals(paymentIntent.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approved authorization currency does not match PaymentIntent");
        }
        if (authorization.getAuthorizedAmount() != paymentIntent.getAmount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approved authorization amount does not match PaymentIntent");
        }
    }

    private void recordRefundAuditLog(MerchantContext merchantContext, String requestId,
            PaymentIntentStatus startingStatus, PaymentIntent paymentIntent, Authorization authorization,
            Refund refund, LedgerService.RefundLedgerResult ledgerResult) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("paymentIntentId", paymentIntent.getId().toString());
        before.put("paymentIntentStatus", startingStatus.name());
        before.put("amount", paymentIntent.getAmount());
        before.put("currency", paymentIntent.getCurrency());
        before.put("authorizationId", authorization.getId().toString());
        before.put("paymentAttemptId", authorization.getPaymentAttemptId().toString());
        before.put("captureTransactionGroupId", ledgerResult.captureTransactionGroupId().toString());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("refundId", refund.getId().toString());
        after.put("refundStatus", refund.getStatus().name());
        after.put("paymentIntentId", paymentIntent.getId().toString());
        after.put("paymentIntentStatus", paymentIntent.getStatus().name());
        after.put("paymentAttemptId", refund.getPaymentAttemptId().toString());
        after.put("authorizationId", refund.getAuthorizationId().toString());
        after.put("amount", refund.getAmount());
        after.put("currency", refund.getCurrency());
        after.put("refundTransactionGroupId", ledgerResult.transactionGroupId().toString());
        after.put("ledgerEntryIds", ledgerResult.entries().stream()
                .map(LedgerEntry::getId)
                .map(UUID::toString)
                .toList());
        auditLogService.recordMerchantApiAction(merchantContext, requestId, "refund.created", "refund",
                refund.getId(), "Refund recorded with balanced capture-reversal ledger entries", before, after);
    }

    private String requestIdFrom(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null || requestId.toString().isBlank()) {
            return "unknown";
        }
        return requestId.toString();
    }

    public record CreateRefundRequest(@NotNull UUID paymentIntentId, @Min(1) long amount) {
    }

    public record RefundResponse(UUID id, UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID authorizationId, long amount, String currency, RefundStatus status, Instant createdAt) {
        static RefundResponse from(Refund refund) {
            return new RefundResponse(refund.getId(), refund.getMerchantId(), refund.getPaymentIntentId(),
                    refund.getPaymentAttemptId(), refund.getAuthorizationId(), refund.getAmount(),
                    refund.getCurrency(), refund.getStatus(), refund.getCreatedAt());
        }
    }
}
