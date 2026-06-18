package com.thaap.payments.payment;

import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.ConfirmDecisionEngine;
import com.thaap.payments.fraud.ConfirmDecisionRequest;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentattempt.PaymentAttemptResponse;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.processor.ProcessorAuthorizationResult;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping("/payment_intents")
public class PaymentIntentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentController.class);

    public static final long MAX_PAYMENT_INTENT_AMOUNT = 100_000_000L;

    private final PaymentIntentRepository paymentIntentRepository;
    private final CustomerRepository customerRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final IdempotencyService idempotencyService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final ConfirmDecisionEngine confirmDecisionEngine;
    private final ProcessorAdapter processorAdapter;
    private final AuthorizationRepository authorizationRepository;

    public PaymentIntentController(PaymentIntentRepository paymentIntentRepository, CustomerRepository customerRepository,
            PaymentMethodRepository paymentMethodRepository, PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyService idempotencyService, WebhookDeliveryService webhookDeliveryService,
            ConfirmDecisionEngine confirmDecisionEngine, ProcessorAdapter processorAdapter,
            AuthorizationRepository authorizationRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.customerRepository = customerRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.idempotencyService = idempotencyService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.confirmDecisionEngine = confirmDecisionEngine;
        this.processorAdapter = processorAdapter;
        this.authorizationRepository = authorizationRepository;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> createPaymentIntent(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.create", request,
                PaymentIntentResponse.class, () -> createPaymentIntentOnce(merchantContext, request));
    }

    protected ResponseEntity<PaymentIntentResponse> createPaymentIntentOnce(MerchantContext merchantContext,
            CreatePaymentIntentRequest request) {
        if (!customerRepository.existsByIdAndMerchantId(request.customerId(), merchantContext.merchantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer was not found for merchant");
        }
        PaymentIntent paymentIntent = paymentIntentRepository.save(new PaymentIntent(merchantContext.merchantId(),
                request.customerId(), request.amount(), request.currency(), request.normalizedCaptureMethod(),
                request.description(), request.normalizedMetadata()));
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "payment_intent.created",
                paymentIntent.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentIntentResponse.from(paymentIntent));
    }

    @GetMapping("/{id}")
    public PaymentIntentResponse getPaymentIntent(MerchantContext merchantContext, @PathVariable UUID id) {
        return PaymentIntentResponse.from(loadPaymentIntent(merchantContext, id));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentAttemptResponse> confirmPaymentIntent(MerchantContext merchantContext,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmPaymentIntentRequest request) {
        // IdempotencyService runs the supplied confirm operation and response storage in one transaction.
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.confirm:" + id,
                request, PaymentAttemptResponse.class,
                () -> confirmPaymentIntentOnce(merchantContext, id, idempotencyKey, request));
    }

    protected ResponseEntity<PaymentAttemptResponse> confirmPaymentIntentOnce(MerchantContext merchantContext, UUID id,
            String idempotencyKey, ConfirmPaymentIntentRequest request) {
        PaymentIntent paymentIntent = loadPaymentIntentForUpdate(merchantContext, id);
        if (paymentIntent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION
                && paymentIntent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PaymentIntent is not confirmable");
        }
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndMerchantId(request.paymentMethodId(), merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentMethod was not found"));
        if (!paymentMethod.getCustomerId().equals(paymentIntent.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentMethod was not found for PaymentIntent");
        }

        int attemptNumber = paymentAttemptRepository.maxAttemptNumber(merchantContext.merchantId(), id) + 1;
        PaymentAttempt paymentAttempt = paymentAttemptRepository.save(new PaymentAttempt(merchantContext.merchantId(), id,
                paymentMethod.getId(), idempotencyKey, attemptNumber));
        ConfirmDecision decision = confirmDecisionEngine.decide(new ConfirmDecisionRequest(merchantContext.merchantId(),
                paymentIntent.getId(), paymentAttempt.getId(), paymentMethod.getId(), paymentIntent.getAmount(),
                paymentIntent.getCurrency()));
        applyConfirmDecision(paymentIntent, paymentAttempt, decision);
        ProcessorAuthorizationResult processorResult = processorAdapter.authorize(new ProcessorAuthorizationRequest(
                merchantContext.merchantId(), paymentIntent.getId(), paymentAttempt.getId(), paymentMethod.getId(),
                paymentIntent.getAmount(), paymentIntent.getCurrency(), paymentMethod.getCardBin(),
                paymentMethod.getCardLast4()));
        Authorization authorization = persistAuthorization(merchantContext, paymentAttempt, processorResult);
        applyProcessorResult(paymentIntent, paymentAttempt, authorization);
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "payment_attempt.created",
                paymentAttempt.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentAttemptResponse.from(paymentAttempt));
    }

    private void applyConfirmDecision(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            ConfirmDecision decision) {
        if (decision.action() != FraudDecisionAction.ALLOW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fraud decision action is not enabled for confirm orchestration");
        }
        paymentAttempt.markReadyForProcessor();
        paymentIntent.markProcessingAfterConfirm();
        log.info("payment_intents.confirm decision applied merchantId={} paymentIntentId={} paymentAttemptId={} "
                        + "action={} reasonCodes={}",
                paymentIntent.getMerchantId(), paymentIntent.getId(), paymentAttempt.getId(), decision.action(),
                decision.reasonCodes());
    }

    private Authorization persistAuthorization(MerchantContext merchantContext, PaymentAttempt paymentAttempt,
            ProcessorAuthorizationResult processorResult) {
        Authorization authorization = new Authorization(merchantContext.merchantId(), paymentAttempt.getId(),
                processorResult.processor(), processorResult.processorReferenceId(), processorResult.responseCode(),
                processorResult.networkStatus(), processorResult.requestedAmount(), processorResult.authorizedAmount(),
                processorResult.currency(), processorResult.status(), processorResult.cvcCheck(),
                processorResult.avsLine1Check(), processorResult.avsPostalCodeCheck(), processorResult.threeDsResult(),
                processorResult.simulatorOutcomeCode());
        return authorizationRepository.save(authorization);
    }

    private void applyProcessorResult(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            Authorization authorization) {
        if (authorization.getStatus() == AuthorizationStatus.APPROVED) {
            paymentAttempt.markAuthorized(authorization.getProcessorReferenceId());
            paymentIntent.markRequiresCaptureAfterAuthorization();
        } else if (authorization.getStatus() == AuthorizationStatus.DECLINED) {
            paymentAttempt.markDeclined(authorization.getProcessorReferenceId());
            paymentIntent.markRequiresPaymentMethodAfterProcessorDecline();
        } else if (authorization.getStatus() == AuthorizationStatus.REQUIRES_ACTION) {
            paymentAttempt.markRequiresAction(authorization.getProcessorReferenceId());
            paymentIntent.markRequiresActionAfterProcessorStepUp();
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Processor authorization status is not supported: " + authorization.getStatus());
        }
        log.info("payment_intents.confirm processor result applied merchantId={} paymentIntentId={} "
                        + "paymentAttemptId={} authorizationId={} status={} responseCode={}",
                paymentIntent.getMerchantId(), paymentIntent.getId(), paymentAttempt.getId(), authorization.getId(),
                authorization.getStatus(), authorization.getProcessorResponseCode());
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

    protected ResponseEntity<PaymentIntentResponse> mutatePaymentIntent(MerchantContext merchantContext, UUID id,
            java.util.function.Consumer<PaymentIntent> mutation, String eventType) {
        PaymentIntent paymentIntent = loadPaymentIntentForUpdate(merchantContext, id);
        mutation.accept(paymentIntent);
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), eventType, paymentIntent.getId());
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentIntent));
    }

    private PaymentIntent loadPaymentIntent(MerchantContext merchantContext, UUID id) {
        return paymentIntentRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentIntent was not found"));
    }

    private PaymentIntent loadPaymentIntentForUpdate(MerchantContext merchantContext, UUID id) {
        return paymentIntentRepository.findByIdAndMerchantIdForUpdate(id, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentIntent was not found"));
    }

    public record CreatePaymentIntentRequest(
            @NotNull UUID customerId,
            @Min(1) @Max(MAX_PAYMENT_INTENT_AMOUNT) long amount,
            @NotBlank @Pattern(regexp = "(?i)USD|EUR|GBP|INR") String currency,
            CaptureMethod captureMethod,
            @Size(max = 500) String description,
            @Size(max = 20) Map<@Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String,
                    @NotNull @Size(max = 500) String> metadata) {

        CaptureMethod normalizedCaptureMethod() {
            return captureMethod == null ? CaptureMethod.AUTOMATIC : captureMethod;
        }

        Map<String, String> normalizedMetadata() {
            return metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record PaymentIntentResponse(UUID id, UUID merchantId, UUID customerId, long amount, String currency,
            CaptureMethod captureMethod, PaymentIntentStatus status, String description, Map<String, String> metadata,
            Instant createdAt, Instant updatedAt) {
        static PaymentIntentResponse from(PaymentIntent paymentIntent) {
            return new PaymentIntentResponse(paymentIntent.getId(), paymentIntent.getMerchantId(),
                    paymentIntent.getCustomerId(), paymentIntent.getAmount(), paymentIntent.getCurrency(),
                    paymentIntent.getCaptureMethod(), paymentIntent.getStatus(), paymentIntent.getDescription(),
                    paymentIntent.getMetadata(), paymentIntent.getCreatedAt(), paymentIntent.getUpdatedAt());
        }
    }

    public record ConfirmPaymentIntentRequest(@NotNull UUID paymentMethodId) {
    }

    private record EmptyMutationRequest() {
    }
}
