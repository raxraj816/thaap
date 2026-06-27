package com.thaap.payments.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.AuthorizationVoid;
import com.thaap.payments.authorization.AuthorizationVoidRepository;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.ConfirmDecisionEngine;
import com.thaap.payments.fraud.ConfirmDecisionRequest;
import com.thaap.payments.fraud.FeatureFreshnessStatus;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FeatureSnapshotRepository;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentattempt.PaymentAttemptResponse;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.processor.ProcessorAuthorizationResult;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    static final int DEFAULT_MAX_FEATURE_SNAPSHOT_JSON_BYTES = 16_384;
    private static final ObjectMapper FEATURE_SNAPSHOT_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final PaymentIntentRepository paymentIntentRepository;
    private final CustomerRepository customerRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final IdempotencyService idempotencyService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final ConfirmDecisionEngine confirmDecisionEngine;
    private final ProcessorAdapter processorAdapter;
    private final AuthorizationRepository authorizationRepository;
    private final AuthorizationVoidRepository authorizationVoidRepository;
    private final FraudDecisionRepository fraudDecisionRepository;
    private final FeatureSnapshotRepository featureSnapshotRepository;
    private final AuditLogService auditLogService;
    private final LedgerService ledgerService;
    private final int maxFeatureSnapshotJsonBytes;
    private final Counter featureSnapshotTooLargeCounter;
    private final Counter featureSnapshotSerializationErrorCounter;

    public PaymentIntentController(PaymentIntentRepository paymentIntentRepository, CustomerRepository customerRepository,
            PaymentMethodRepository paymentMethodRepository, PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyService idempotencyService, WebhookDeliveryService webhookDeliveryService,
            ConfirmDecisionEngine confirmDecisionEngine, ProcessorAdapter processorAdapter,
            AuthorizationRepository authorizationRepository, AuthorizationVoidRepository authorizationVoidRepository,
            FraudDecisionRepository fraudDecisionRepository, FeatureSnapshotRepository featureSnapshotRepository,
            AuditLogService auditLogService, LedgerService ledgerService, MeterRegistry meterRegistry,
            @Value("${thaap.fraud.feature-snapshot.max-json-bytes:16384}") int maxFeatureSnapshotJsonBytes) {
        if (maxFeatureSnapshotJsonBytes <= 0) {
            throw new IllegalArgumentException("thaap.fraud.feature-snapshot.max-json-bytes must be positive");
        }
        this.paymentIntentRepository = paymentIntentRepository;
        this.customerRepository = customerRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.idempotencyService = idempotencyService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.confirmDecisionEngine = confirmDecisionEngine;
        this.processorAdapter = processorAdapter;
        this.authorizationRepository = authorizationRepository;
        this.authorizationVoidRepository = authorizationVoidRepository;
        this.fraudDecisionRepository = fraudDecisionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.auditLogService = auditLogService;
        this.ledgerService = ledgerService;
        this.maxFeatureSnapshotJsonBytes = maxFeatureSnapshotJsonBytes;
        this.featureSnapshotTooLargeCounter = meterRegistry.counter("thaap.feature.snapshot.too_large");
        this.featureSnapshotSerializationErrorCounter = meterRegistry.counter(
                "thaap.feature.snapshot.serialization_errors");
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
            @Valid @RequestBody ConfirmPaymentIntentRequest request, HttpServletRequest httpServletRequest) {
        // IdempotencyService runs the supplied confirm operation and response storage in one transaction.
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.confirm:" + id,
                request, PaymentAttemptResponse.class,
                () -> confirmPaymentIntentOnce(merchantContext, id, idempotencyKey, request,
                        requestIdFrom(httpServletRequest)));
    }

    protected ResponseEntity<PaymentAttemptResponse> confirmPaymentIntentOnce(MerchantContext merchantContext, UUID id,
            String idempotencyKey, ConfirmPaymentIntentRequest request, String requestId) {
        PaymentIntent paymentIntent = loadPaymentIntentForUpdate(merchantContext, id);
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
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
        FeatureSnapshotDraft featureSnapshotDraft = buildFeatureSnapshot(paymentIntent, paymentAttempt, paymentMethod);
        Instant decisionStartedAt = Instant.now();
        ConfirmDecision decision = confirmDecisionEngine.decide(new ConfirmDecisionRequest(merchantContext.merchantId(),
                paymentIntent.getId(), paymentAttempt.getId(), paymentMethod.getId(), paymentIntent.getAmount(),
                paymentIntent.getCurrency(), paymentIntent.getCaptureMethod(), paymentAttempt.getAttemptNumber(),
                paymentMethod.getCardBin(), paymentMethod.getCardLast4(), paymentMethod.getCardCountry(),
                paymentMethod.getCardFunding().name()));
        Instant decisionCompletedAt = Instant.now();
        long latencyMs = Math.max(0L, Duration.between(decisionStartedAt, decisionCompletedAt).toMillis());
        FraudDecision fraudDecision = persistFraudLineage(merchantContext, paymentAttempt, featureSnapshotDraft,
                decision, latencyMs, decisionStartedAt, decisionCompletedAt);
        paymentAttempt.attachFraudDecision(fraudDecision.getId());
        applyConfirmDecision(paymentIntent, paymentAttempt, decision);
        ProcessorAuthorizationResult processorResult = processorAdapter.authorize(new ProcessorAuthorizationRequest(
                merchantContext.merchantId(), paymentIntent.getId(), paymentAttempt.getId(), paymentMethod.getId(),
                paymentIntent.getAmount(), paymentIntent.getCurrency(), paymentMethod.getCardBin(),
                paymentMethod.getCardLast4()));
        Authorization authorization = persistAuthorization(merchantContext, paymentAttempt, processorResult);
        applyProcessorResult(paymentIntent, paymentAttempt, authorization);
        recordConfirmAuditLogs(merchantContext, requestId, startingStatus, paymentIntent, paymentAttempt, fraudDecision,
                authorization);
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "payment_attempt.created",
                paymentAttempt.getId());
        if (authorization.getStatus() == AuthorizationStatus.APPROVED
                && paymentIntent.getCaptureMethod() == CaptureMethod.AUTOMATIC) {
            captureApprovedPaymentIntent(merchantContext, requestId, paymentIntent, authorization,
                    CaptureTrigger.AUTOMATIC_CONFIRM);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentAttemptResponse.from(paymentAttempt));
    }

    private FeatureSnapshotDraft buildFeatureSnapshot(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod) {
        Instant computedAt = Instant.now();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("payment_amount", paymentIntent.getAmount());
        features.put("payment_currency", paymentIntent.getCurrency());
        features.put("capture_method", paymentIntent.getCaptureMethod().name());
        features.put("attempt_number", paymentAttempt.getAttemptNumber());
        features.put("card_bin", paymentMethod.getCardBin());
        features.put("card_last4", paymentMethod.getCardLast4());
        features.put("card_country", paymentMethod.getCardCountry());
        features.put("card_funding", paymentMethod.getCardFunding().name());
        features.put("payment_intent_id", paymentIntent.getId().toString());
        features.put("payment_attempt_id", paymentAttempt.getId().toString());
        features.put("payment_method_id", paymentMethod.getId().toString());
        requireFeatureSnapshotWithinStorageGuard(features);
        long maxFeatureAgeMs = 0L;
        return new FeatureSnapshotDraft(features, computedAt, computedAt, maxFeatureAgeMs,
                FeatureFreshnessStatus.FRESH, List.of(), List.of());
    }

    private FraudDecision persistFraudLineage(MerchantContext merchantContext, PaymentAttempt paymentAttempt,
            FeatureSnapshotDraft featureSnapshotDraft, ConfirmDecision decision, long latencyMs,
            Instant decisionStartedAt, Instant decisionCompletedAt) {
        // IdempotencyService keeps lineage, attempt mutation, authorization, and completion in one transaction.
        // A later failure rolls these writes back together instead of leaving partial confirm evidence.
        FraudDecision fraudDecision = fraudDecisionRepository.save(new FraudDecision(merchantContext.merchantId(),
                paymentAttempt.getId(), decision, latencyMs, decisionStartedAt, decisionCompletedAt,
                featureSnapshotDraft.freshnessStatus(), featureSnapshotDraft.maxFeatureAgeMs()));
        featureSnapshotRepository.save(new FeatureSnapshot(merchantContext.merchantId(), fraudDecision.getId(),
                paymentAttempt.getId(), featureSnapshotDraft.features(), featureSnapshotDraft.computedAt(),
                featureSnapshotDraft.oldestFeatureTimestamp(), featureSnapshotDraft.maxFeatureAgeMs(),
                featureSnapshotDraft.freshnessStatus(), featureSnapshotDraft.staleFeatureNames(),
                featureSnapshotDraft.missingFeatureNames()));
        return fraudDecision;
    }

    private void requireFeatureSnapshotWithinStorageGuard(Map<String, Object> features) {
        try {
            byte[] serialized = FEATURE_SNAPSHOT_OBJECT_MAPPER.writeValueAsString(features)
                    .getBytes(StandardCharsets.UTF_8);
            if (serialized.length > maxFeatureSnapshotJsonBytes) {
                featureSnapshotTooLargeCounter.increment();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Feature snapshot exceeded safe storage limit");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            featureSnapshotSerializationErrorCounter.increment();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Feature snapshot could not be serialized", exception);
        }
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

    private void recordConfirmAuditLogs(MerchantContext merchantContext, String requestId,
            PaymentIntentStatus startingStatus, PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            FraudDecision fraudDecision, Authorization authorization) {
        List<ConfirmAuditEventMapper.ConfirmAuditEvent> auditEvents = ConfirmAuditEventMapper.eventsForConfirm(
                startingStatus, paymentIntent, paymentAttempt, fraudDecision, authorization);
        for (ConfirmAuditEventMapper.ConfirmAuditEvent auditEvent : auditEvents) {
            auditLogService.recordMerchantApiAction(merchantContext, requestId, auditEvent.action(),
                    auditEvent.aggregateType(), auditEvent.aggregateId(), auditEvent.reason(), auditEvent.before(),
                    auditEvent.after());
        }
    }

    private String requestIdFrom(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null || requestId.toString().isBlank()) {
            return "unknown";
        }
        return requestId.toString();
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentIntentResponse> capturePaymentIntent(MerchantContext merchantContext,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpServletRequest) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.capture:" + id,
                new EmptyMutationRequest(), PaymentIntentResponse.class,
                () -> capturePaymentIntentOnce(merchantContext, id, requestIdFrom(httpServletRequest)));
    }

    protected ResponseEntity<PaymentIntentResponse> capturePaymentIntentOnce(MerchantContext merchantContext, UUID id,
            String requestId) {
        PaymentIntent paymentIntent = loadPaymentIntentForUpdate(merchantContext, id);
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        if (startingStatus != PaymentIntentStatus.REQUIRES_CAPTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be captured from status " + startingStatus);
        }
        if (paymentIntent.getCaptureMethod() != CaptureMethod.MANUAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only MANUAL PaymentIntents can use the capture endpoint");
        }
        Authorization authorization = latestApprovedAuthorization(merchantContext, id);
        captureApprovedPaymentIntent(merchantContext, requestId, paymentIntent, authorization,
                CaptureTrigger.MANUAL_API);
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentIntent));
    }

    private void captureApprovedPaymentIntent(MerchantContext merchantContext, String requestId,
            PaymentIntent paymentIntent, Authorization authorization, CaptureTrigger captureTrigger) {
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        if (startingStatus != PaymentIntentStatus.REQUIRES_CAPTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be captured from status " + startingStatus);
        }
        if (authorizationVoidRepository.existsByAuthorizationIdAndMerchantId(authorization.getId(),
                merchantContext.merchantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approved authorization has been voided");
        }
        if (!authorization.getCurrency().equals(paymentIntent.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approved authorization currency does not match PaymentIntent");
        }
        if (authorization.getAuthorizedAmount() != paymentIntent.getAmount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approved authorization amount does not match PaymentIntent");
        }
        LedgerService.CaptureLedgerResult ledgerResult = ledgerService.recordCapture(merchantContext.merchantId(),
                paymentIntent.getId(), authorization.getAuthorizedAmount(), authorization.getCurrency());
        paymentIntent.capture();
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "payment_intent.captured",
                paymentIntent.getId());
        recordCaptureAuditLog(merchantContext, requestId, startingStatus, paymentIntent, authorization, ledgerResult,
                captureTrigger);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentIntentResponse> cancelPaymentIntent(MerchantContext merchantContext,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpServletRequest) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.cancel:" + id,
                new EmptyMutationRequest(), PaymentIntentResponse.class,
                () -> cancelPaymentIntentOnce(merchantContext, id, requestIdFrom(httpServletRequest)));
    }

    protected ResponseEntity<PaymentIntentResponse> cancelPaymentIntentOnce(MerchantContext merchantContext, UUID id,
            String requestId) {
        PaymentIntent paymentIntent = loadPaymentIntentForUpdate(merchantContext, id);
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        Authorization authorization = null;
        AuthorizationVoid authorizationVoid = null;
        if (startingStatus == PaymentIntentStatus.AUTHORIZED
                || startingStatus == PaymentIntentStatus.REQUIRES_CAPTURE) {
            authorization = latestApprovedAuthorization(merchantContext, id);
            authorizationVoid = authorizationVoidRepository.save(new AuthorizationVoid(merchantContext.merchantId(),
                    authorization.getId(), authorization.getPaymentAttemptId(), paymentIntent.getId()));
        }
        paymentIntent.cancel();
        webhookDeliveryService.createDeliveries(merchantContext.merchantId(), "payment_intent.canceled",
                paymentIntent.getId());
        recordCancelAuditLog(merchantContext, requestId, startingStatus, paymentIntent, authorization,
                authorizationVoid);
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentIntent));
    }

    private Authorization latestApprovedAuthorization(MerchantContext merchantContext, UUID paymentIntentId) {
        return authorizationRepository.findApprovedAuthorizationsForPaymentIntent(merchantContext.merchantId(),
                        paymentIntentId, PaymentAttemptStatus.AUTHORIZED, AuthorizationStatus.APPROVED)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "PaymentIntent does not have an approved authorization to capture"));
    }

    private void recordCaptureAuditLog(MerchantContext merchantContext, String requestId,
            PaymentIntentStatus startingStatus, PaymentIntent paymentIntent, Authorization authorization,
            LedgerService.CaptureLedgerResult ledgerResult, CaptureTrigger captureTrigger) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("paymentIntentId", paymentIntent.getId().toString());
        before.put("status", startingStatus.name());
        before.put("amount", paymentIntent.getAmount());
        before.put("currency", paymentIntent.getCurrency());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("paymentIntentId", paymentIntent.getId().toString());
        after.put("status", paymentIntent.getStatus().name());
        after.put("captureAmount", authorization.getAuthorizedAmount());
        after.put("captureMethod", paymentIntent.getCaptureMethod().name());
        after.put("captureTrigger", captureTrigger.name());
        after.put("currency", authorization.getCurrency());
        after.put("authorizationId", authorization.getId().toString());
        after.put("transactionGroupId", ledgerResult.transactionGroupId().toString());
        after.put("ledgerEntryIds", ledgerResult.entries().stream()
                .map(LedgerEntry::getId)
                .map(UUID::toString)
                .toList());
        auditLogService.recordMerchantApiAction(merchantContext, requestId, "payment_intent.captured",
                "payment_intent", paymentIntent.getId(), "PaymentIntent captured with balanced ledger entries",
                before, after);
    }

    private void recordCancelAuditLog(MerchantContext merchantContext, String requestId,
            PaymentIntentStatus startingStatus, PaymentIntent paymentIntent, Authorization authorization,
            AuthorizationVoid authorizationVoid) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("paymentIntentId", paymentIntent.getId().toString());
        before.put("status", startingStatus.name());
        before.put("amount", paymentIntent.getAmount());
        before.put("currency", paymentIntent.getCurrency());
        before.put("captureMethod", paymentIntent.getCaptureMethod().name());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("paymentIntentId", paymentIntent.getId().toString());
        after.put("status", paymentIntent.getStatus().name());
        after.put("voidRequired", authorizationVoid != null);
        if (authorization != null && authorizationVoid != null) {
            after.put("authorizationId", authorization.getId().toString());
            after.put("authorizationVoidId", authorizationVoid.getId().toString());
            after.put("authorizationVoidStatus", authorizationVoid.getStatus().name());
            after.put("authorizationVoidReason", authorizationVoid.getReason().name());
        }
        auditLogService.recordMerchantApiAction(merchantContext, requestId, "payment_intent.canceled",
                "payment_intent", paymentIntent.getId(), authorizationVoid == null
                        ? "PaymentIntent canceled before approved authorization"
                        : "PaymentIntent canceled and approved authorization void recorded",
                before, after);
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

    private record FeatureSnapshotDraft(Map<String, Object> features, Instant computedAt,
            Instant oldestFeatureTimestamp, long maxFeatureAgeMs, FeatureFreshnessStatus freshnessStatus,
            List<String> staleFeatureNames, List<String> missingFeatureNames) {
    }

    private enum CaptureTrigger {
        AUTOMATIC_CONFIRM,
        MANUAL_API
    }
}
