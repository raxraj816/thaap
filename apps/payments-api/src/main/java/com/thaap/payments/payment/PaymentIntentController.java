package com.thaap.payments.payment;

import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/payment_intents")
public class PaymentIntentController {

    private final PaymentIntentRepository paymentIntentRepository;
    private final CustomerRepository customerRepository;
    private final IdempotencyService idempotencyService;
    private final WebhookDeliveryService webhookDeliveryService;

    public PaymentIntentController(PaymentIntentRepository paymentIntentRepository, CustomerRepository customerRepository,
            IdempotencyService idempotencyService, WebhookDeliveryService webhookDeliveryService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.customerRepository = customerRepository;
        this.idempotencyService = idempotencyService;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> createPaymentIntent(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.create", request,
                PaymentIntentResponse.class, () -> createPaymentIntentOnce(merchantContext, request));
    }

    @Transactional
    protected ResponseEntity<PaymentIntentResponse> createPaymentIntentOnce(MerchantContext merchantContext,
            CreatePaymentIntentRequest request) {
        if (!customerRepository.existsByIdAndMerchantId(request.customerId(), merchantContext.merchantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer was not found for merchant");
        }
        var paymentIntent = paymentIntentRepository.save(new PaymentIntent(merchantContext.merchantId(),
                request.customerId(), request.amount(), request.currency(), request.normalizedCaptureMethod()));
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "payment_intent.created",
                paymentIntent.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentIntentResponse.from(paymentIntent));
    }

    @GetMapping("/{id}")
    public PaymentIntentResponse getPaymentIntent(MerchantContext merchantContext, @PathVariable UUID id) {
        return PaymentIntentResponse.from(loadPaymentIntent(merchantContext, id));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentIntentResponse> capturePaymentIntent(MerchantContext merchantContext,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.capture:" + id,
                new EmptyMutationRequest(), PaymentIntentResponse.class, () -> mutatePaymentIntent(merchantContext, id,
                        PaymentIntent::capture, "payment_intent.captured"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentIntentResponse> cancelPaymentIntent(MerchantContext merchantContext,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.cancel:" + id,
                new EmptyMutationRequest(), PaymentIntentResponse.class, () -> mutatePaymentIntent(merchantContext, id,
                        PaymentIntent::cancel, "payment_intent.canceled"));
    }

    @Transactional
    protected ResponseEntity<PaymentIntentResponse> mutatePaymentIntent(MerchantContext merchantContext, UUID id,
            java.util.function.Consumer<PaymentIntent> mutation, String eventType) {
        var paymentIntent = loadPaymentIntent(merchantContext, id);
        mutation.accept(paymentIntent);
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), eventType, paymentIntent.getId());
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentIntent));
    }

    private PaymentIntent loadPaymentIntent(MerchantContext merchantContext, UUID id) {
        return paymentIntentRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentIntent was not found"));
    }

    public record CreatePaymentIntentRequest(
            @NotNull UUID customerId,
            @Min(1) long amount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
            CaptureMethod captureMethod) {

        CaptureMethod normalizedCaptureMethod() {
            return captureMethod == null ? CaptureMethod.AUTOMATIC : captureMethod;
        }
    }

    public record PaymentIntentResponse(UUID id, UUID merchantId, UUID customerId, long amount, String currency,
            CaptureMethod captureMethod, PaymentIntentStatus status) {
        static PaymentIntentResponse from(PaymentIntent paymentIntent) {
            return new PaymentIntentResponse(paymentIntent.getId(), paymentIntent.getMerchantId(),
                    paymentIntent.getCustomerId(), paymentIntent.getAmount(), paymentIntent.getCurrency(),
                    paymentIntent.getCaptureMethod(), paymentIntent.getStatus());
        }
    }

    private record EmptyMutationRequest() {
    }
}
