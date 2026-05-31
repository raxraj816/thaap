package com.thaap.payments.payment;

import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
    private final IdempotencyService idempotencyService;
    private final WebhookDeliveryService webhookDeliveryService;

    public RefundController(PaymentIntentRepository paymentIntentRepository, IdempotencyService idempotencyService,
            WebhookDeliveryService webhookDeliveryService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.idempotencyService = idempotencyService;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> createRefund(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "refunds.create", request,
                RefundResponse.class, () -> createRefundOnce(merchantContext, request));
    }

    @Transactional
    protected ResponseEntity<RefundResponse> createRefundOnce(MerchantContext merchantContext,
            CreateRefundRequest request) {
        var paymentIntent = paymentIntentRepository
                .findByIdAndMerchantId(request.paymentIntentId(), merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentIntent was not found"));
        paymentIntent.refund();
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "refund.created", paymentIntent.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RefundResponse(UUID.randomUUID(), merchantContext.merchantId(), paymentIntent.getId(),
                        request.amount(), paymentIntent.getCurrency()));
    }

    public record CreateRefundRequest(@NotNull UUID paymentIntentId, @Min(1) long amount) {
    }

    public record RefundResponse(UUID id, UUID merchantId, UUID paymentIntentId, long amount, String currency) {
    }
}
