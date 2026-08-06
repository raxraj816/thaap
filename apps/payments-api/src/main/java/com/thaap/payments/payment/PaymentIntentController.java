package com.thaap.payments.payment;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.authentication.AuthenticationChallengeService;
import com.thaap.payments.authentication.AuthenticationChallengeSource;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.AuthorizationVoid;
import com.thaap.payments.authorization.AuthorizationVoidRepository;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.controls.ActiveAuthoredRuleEvaluator;
import com.thaap.payments.controls.AuthoredRuleAssessment;
import com.thaap.payments.controls.AuthoredRuleDecisionAggregator;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.ConfirmDecisionEngine;
import com.thaap.payments.fraud.FeatureMaterializationMetrics;
import com.thaap.payments.fraud.FeatureMaterializer;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FeatureSnapshotRepository;
import com.thaap.payments.fraud.FeatureValue;
import com.thaap.payments.fraud.FeatureValueRepository;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.fraud.FraudDecisionMetrics;
import com.thaap.payments.fraud.MaterializedFeatureSet;
import com.thaap.payments.fraud.RuleEvaluation;
import com.thaap.payments.fraud.RuleExecutionResult;
import com.thaap.payments.fraud.RuleExecutionResultRepository;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.fraudlist.FraudListAssessment;
import com.thaap.payments.fraudlist.FraudListDecisionAggregator;
import com.thaap.payments.fraudlist.FraudListEvaluationRecord;
import com.thaap.payments.fraudlist.FraudListEvaluation;
import com.thaap.payments.fraudlist.FraudListEvaluationRecordRepository;
import com.thaap.payments.fraudlist.FraudListEvaluator;
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
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.riskcontext.RiskContextRequest;
import com.thaap.payments.riskcontext.RiskContextService;
import com.thaap.payments.riskpolicy.MerchantPolicyAssessment;
import com.thaap.payments.riskpolicy.MerchantRiskConfiguration;
import com.thaap.payments.riskpolicy.MerchantRiskPolicyDecisionAggregator;
import com.thaap.payments.incident.CardTestingIncidentService;
import com.thaap.payments.ratelimit.RateLimitOperation;
import com.thaap.payments.ratelimit.RequestRateLimitService;
import com.thaap.payments.riskpolicy.MerchantRiskPolicyEvaluator;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.velocity.VelocityAssessment;
import com.thaap.payments.velocity.VelocityDecisionAggregator;
import com.thaap.payments.velocity.VelocityRiskCoordinator;
import com.thaap.payments.webhook.WebhookDeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
    static final int DEFAULT_MAX_FEATURE_SNAPSHOT_JSON_BYTES = 16_384;

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
    private final FeatureValueRepository featureValueRepository;
    private final FeatureMaterializer featureMaterializer;
    private final FeatureMaterializationMetrics featureMaterializationMetrics;
    private final RuleExecutionResultRepository ruleExecutionResultRepository;
    private final ManualReviewCaseRepository manualReviewCaseRepository;
    private final FraudDecisionMetrics fraudDecisionMetrics;
    private final AuditLogService auditLogService;
    private final LedgerService ledgerService;
    private final AuthenticationChallengeService authenticationChallengeService;
    private final RiskContextService riskContextService;
    private final VelocityRiskCoordinator velocityRiskCoordinator;
    private final VelocityDecisionAggregator velocityDecisionAggregator;
    private final FraudListEvaluator fraudListEvaluator;
    private final FraudListDecisionAggregator fraudListDecisionAggregator;
    private final FraudListEvaluationRecordRepository fraudListEvaluationRecordRepository;
    private final MerchantRiskPolicyEvaluator merchantRiskPolicyEvaluator;
    private final MerchantRiskPolicyDecisionAggregator merchantRiskPolicyDecisionAggregator;
    private final RequestRateLimitService requestRateLimitService;
    private final CardTestingIncidentService cardTestingIncidentService;
    private final ActiveAuthoredRuleEvaluator activeAuthoredRuleEvaluator;
    private final AuthoredRuleDecisionAggregator authoredRuleDecisionAggregator;
    private final PaymentDomainEventService paymentDomainEventService;
    private final RiskOperationsEventProducer riskOperationsEventProducer;

    public PaymentIntentController(PaymentIntentRepository paymentIntentRepository, CustomerRepository customerRepository,
            PaymentMethodRepository paymentMethodRepository, PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyService idempotencyService, WebhookDeliveryService webhookDeliveryService,
            ConfirmDecisionEngine confirmDecisionEngine, ProcessorAdapter processorAdapter,
            AuthorizationRepository authorizationRepository, AuthorizationVoidRepository authorizationVoidRepository,
            FraudDecisionRepository fraudDecisionRepository, FeatureSnapshotRepository featureSnapshotRepository,
            FeatureValueRepository featureValueRepository, FeatureMaterializer featureMaterializer,
            FeatureMaterializationMetrics featureMaterializationMetrics,
            RuleExecutionResultRepository ruleExecutionResultRepository,
            ManualReviewCaseRepository manualReviewCaseRepository,
            FraudDecisionMetrics fraudDecisionMetrics,
            AuditLogService auditLogService, LedgerService ledgerService,
            AuthenticationChallengeService authenticationChallengeService,
            RiskContextService riskContextService, VelocityRiskCoordinator velocityRiskCoordinator,
            VelocityDecisionAggregator velocityDecisionAggregator, FraudListEvaluator fraudListEvaluator,
            FraudListDecisionAggregator fraudListDecisionAggregator,
            FraudListEvaluationRecordRepository fraudListEvaluationRecordRepository,
            MerchantRiskPolicyEvaluator merchantRiskPolicyEvaluator,
            MerchantRiskPolicyDecisionAggregator merchantRiskPolicyDecisionAggregator,
            RequestRateLimitService requestRateLimitService,
            CardTestingIncidentService cardTestingIncidentService,
            ActiveAuthoredRuleEvaluator activeAuthoredRuleEvaluator,
            AuthoredRuleDecisionAggregator authoredRuleDecisionAggregator,
            PaymentDomainEventService paymentDomainEventService,
            RiskOperationsEventProducer riskOperationsEventProducer) {
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
        this.featureValueRepository = featureValueRepository;
        this.featureMaterializer = featureMaterializer;
        this.featureMaterializationMetrics = featureMaterializationMetrics;
        this.ruleExecutionResultRepository = ruleExecutionResultRepository;
        this.manualReviewCaseRepository = manualReviewCaseRepository;
        this.fraudDecisionMetrics = fraudDecisionMetrics;
        this.auditLogService = auditLogService;
        this.ledgerService = ledgerService;
        this.authenticationChallengeService = authenticationChallengeService;
        this.riskContextService = riskContextService;
        this.velocityRiskCoordinator = velocityRiskCoordinator;
        this.velocityDecisionAggregator = velocityDecisionAggregator;
        this.fraudListEvaluator = fraudListEvaluator;
        this.fraudListDecisionAggregator = fraudListDecisionAggregator;
        this.fraudListEvaluationRecordRepository = fraudListEvaluationRecordRepository;
        this.merchantRiskPolicyEvaluator = merchantRiskPolicyEvaluator;
        this.merchantRiskPolicyDecisionAggregator = merchantRiskPolicyDecisionAggregator;
        this.requestRateLimitService = requestRateLimitService;
        this.cardTestingIncidentService = cardTestingIncidentService;
        this.activeAuthoredRuleEvaluator = activeAuthoredRuleEvaluator;
        this.authoredRuleDecisionAggregator = authoredRuleDecisionAggregator;
        this.paymentDomainEventService = paymentDomainEventService;
        this.riskOperationsEventProducer = riskOperationsEventProducer;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> createPaymentIntent(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request, HttpServletRequest httpServletRequest) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_intents.create", request,
                PaymentIntentResponse.class, () -> createPaymentIntentOnce(
                        merchantContext, request, requestIdFrom(httpServletRequest)));
    }

    protected ResponseEntity<PaymentIntentResponse> createPaymentIntentOnce(MerchantContext merchantContext,
            CreatePaymentIntentRequest request, String requestId) {
        if (!customerRepository.existsByIdAndMerchantId(request.customerId(), merchantContext.merchantId())) {
            throw new PublicApiException(PublicApiErrorDescriptor.CUSTOMER_NOT_FOUND, "Customer was not found for merchant");
        }
        PaymentIntent paymentIntent = paymentIntentRepository.save(new PaymentIntent(merchantContext.merchantId(),
                request.customerId(), request.amount(), request.currency(), request.normalizedCaptureMethod(),
                request.description(), request.normalizedMetadata(), merchantContext.trafficOrigin()));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(
                merchantContext.merchantId(), "payment_intent.created",
                paymentIntent.getId(), paymentIntent.getTrafficOrigin());
        paymentDomainEventService.paymentIntentCreated(paymentIntent,
                paymentDomainEventService.merchantActor(merchantContext), requestId);
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
                        requestIdFrom(httpServletRequest), httpServletRequest.getRemoteAddr()));
    }

    protected ResponseEntity<PaymentAttemptResponse> confirmPaymentIntentOnce(MerchantContext merchantContext, UUID id,
            String idempotencyKey, ConfirmPaymentIntentRequest request, String requestId, String clientIp) {
        requestRateLimitService.enforce(RateLimitOperation.PAYMENT_INTENT_CONFIRM,
                merchantContext, idempotencyKey, clientIp);
        PaymentIntent paymentIntent = loadPaymentIntentForUpdate(merchantContext, id);
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        if (paymentIntent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION
                && paymentIntent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CONFIRMABLE, "PaymentIntent is not confirmable");
        }
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndMerchantId(request.paymentMethodId(), merchantContext.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_METHOD_NOT_FOUND, "PaymentMethod was not found"));
        if (!paymentMethod.getCustomerId().equals(paymentIntent.getCustomerId())) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_PAYMENT_METHOD_NOT_FOUND, "PaymentMethod was not found for PaymentIntent");
        }

        int attemptNumber = paymentAttemptRepository.maxAttemptNumber(merchantContext.merchantId(), id) + 1;
        PaymentAttempt paymentAttempt = paymentAttemptRepository.save(new PaymentAttempt(merchantContext.merchantId(), id,
                paymentMethod.getId(), idempotencyKey, attemptNumber, paymentIntent.getTrafficOrigin()));
        RiskContext riskContext = riskContextService.create(merchantContext.merchantId(), paymentIntent.getId(),
                paymentAttempt.getId(), request.riskContext(), paymentAttempt.getTrafficOrigin());
        VelocityAssessment velocityAssessment = velocityRiskCoordinator.assess(
                paymentIntent, paymentAttempt, paymentMethod, riskContext);
        FraudListAssessment fraudListAssessment = fraudListEvaluator.evaluate(
                paymentIntent, paymentMethod, riskContext);
        Instant decisionStartedAt = Instant.now();
        MerchantRiskConfiguration merchantRiskConfiguration = merchantRiskPolicyEvaluator.loadConfiguration(
                merchantContext.merchantId(), decisionStartedAt);
        MaterializedFeatureSet materializedFeatures = featureMaterializer.materialize(
                paymentIntent, paymentAttempt, paymentMethod, riskContext, velocityAssessment,
                merchantRiskConfiguration.riskTier(), decisionStartedAt);
        MerchantPolicyAssessment merchantPolicyAssessment = merchantRiskPolicyEvaluator.evaluate(
                merchantRiskConfiguration, materializedFeatures, paymentIntent.getCurrency());
        AuthoredRuleAssessment authoredRuleAssessment = activeAuthoredRuleEvaluator.evaluate(
                merchantContext.merchantId(), paymentMethod, materializedFeatures, decisionStartedAt);
        long engineStartedNanos = System.nanoTime();
        ConfirmDecision decision;
        try {
            ConfirmDecision baseDecision = confirmDecisionEngine.decide(materializedFeatures.decisionFeatureContext());
            ConfirmDecision velocityDecision = velocityDecisionAggregator.aggregate(baseDecision, velocityAssessment);
            ConfirmDecision listDecision = fraudListDecisionAggregator.aggregate(velocityDecision, fraudListAssessment);
            ConfirmDecision policyDecision = merchantRiskPolicyDecisionAggregator.aggregate(
                    listDecision, merchantPolicyAssessment);
            decision = authoredRuleDecisionAggregator.aggregate(policyDecision, authoredRuleAssessment);
        } catch (RuntimeException exception) {
            fraudDecisionMetrics.recordFailure("EVALUATION", "ENGINE_FAILURE");
            throw exception;
        }
        long engineElapsedNanos = Math.max(0L, System.nanoTime() - engineStartedNanos);
        fraudDecisionMetrics.observeDecisionTransaction(
                decision, paymentAttempt.getTrafficOrigin(), engineElapsedNanos);
        Instant decisionCompletedAt = Instant.now();
        long latencyMs = Math.max(0L, Duration.between(decisionStartedAt, decisionCompletedAt).toMillis());
        PersistedFraudLineage persistedFraudLineage = persistFraudLineage(
                merchantContext, paymentAttempt, materializedFeatures,
                fraudListAssessment, merchantPolicyAssessment, authoredRuleAssessment, decision, latencyMs,
                decisionStartedAt, decisionCompletedAt);
        FraudDecision fraudDecision = persistedFraudLineage.decision();
        if (riskContext != null) {
            riskContext.attachFraudDecision(fraudDecision.getId());
        }
        velocityAssessment.attachFraudDecision(fraudDecision.getId());
        paymentAttempt.attachFraudDecision(fraudDecision.getId());
        applyConfirmDecision(paymentIntent, paymentAttempt, decision);
        Authorization authorization = null;
        ManualReviewCase manualReviewCase = null;
        AuthenticationChallengeService.CreatedChallenge createdChallenge = null;
        if (decision.action() == FraudDecisionAction.ALLOW) {
            ProcessorAuthorizationResult processorResult = processorAdapter.authorize(new ProcessorAuthorizationRequest(
                    merchantContext.merchantId(), paymentIntent.getId(), paymentAttempt.getId(), paymentMethod.getId(),
                    paymentIntent.getAmount(), paymentIntent.getCurrency(), paymentMethod.getCardBin(),
                    paymentMethod.getCardLast4()));
            authorization = persistAuthorization(merchantContext, paymentAttempt, processorResult);
            applyProcessorResult(paymentIntent, paymentAttempt, authorization);
            if (authorization.getStatus() == AuthorizationStatus.REQUIRES_ACTION) {
                createdChallenge = authenticationChallengeService.create(
                        merchantContext.merchantId(), paymentIntent.getId(), paymentAttempt.getId(),
                        fraudDecision.getId(), authorization.getId(), AuthenticationChallengeSource.PROCESSOR,
                        paymentAttempt.getTrafficOrigin());
            }
        } else if (decision.action() == FraudDecisionAction.REQUEST_3DS) {
            createdChallenge = authenticationChallengeService.create(
                    merchantContext.merchantId(), paymentIntent.getId(), paymentAttempt.getId(),
                    fraudDecision.getId(), null, AuthenticationChallengeSource.FRAUD_ENGINE,
                    paymentAttempt.getTrafficOrigin());
        } else if (decision.action() == FraudDecisionAction.REVIEW) {
            manualReviewCase = manualReviewCaseRepository.save(new ManualReviewCase(
                    merchantContext.merchantId(), paymentIntent.getId(),
                    paymentAttempt.getId(), fraudDecision.getId(), decision.reasonCodes(),
                    paymentAttempt.getTrafficOrigin()));
        }
        recordConfirmAuditLogs(merchantContext, requestId, startingStatus, paymentIntent, paymentAttempt, fraudDecision,
                authorization);
        webhookDeliveryService.createDeliveriesInCurrentTransaction(
                merchantContext.merchantId(), "payment_attempt.created",
                paymentAttempt.getId(), paymentAttempt.getTrafficOrigin());
        if (decision.action() != FraudDecisionAction.ALLOW) {
            webhookDeliveryService.createDeliveriesInCurrentTransaction(
                    merchantContext.merchantId(), eventTypeFor(decision.action()),
                    manualReviewCase == null ? paymentIntent.getId() : manualReviewCase.getId(),
                    paymentIntent.getTrafficOrigin());
        }
        paymentAttemptRepository.flush();
        fraudDecisionRepository.flush();
        if (authorization != null) {
            authorizationRepository.flush();
        }
        cardTestingIncidentService.detectFromPersistedEvidence(merchantContext, requestId);
        DomainEventActor eventActor = paymentDomainEventService.merchantActor(merchantContext);
        DomainEventWriteResult fraudEvent = paymentDomainEventService.fraudDecisionCreated(
                paymentIntent, fraudDecision, persistedFraudLineage.featureSnapshot(), eventActor,
                requestId, null);
        if (manualReviewCase != null) {
            riskOperationsEventProducer.manualReviewOpenedBySystem(merchantContext.merchantId(),
                    paymentIntent.getId(), fraudDecision.getDecisionCompletedAt(), requestId,
                    paymentIntent.getTrafficOrigin(), "STANDARD",
                    decision.reasonCodes().stream().map(Enum::name).toList());
        }
        DomainEventWriteResult authorizationEvent = null;
        if (authorization != null && authorization.getStatus() == AuthorizationStatus.APPROVED) {
            authorizationEvent = paymentDomainEventService.authorizationCreated(paymentIntent,
                    paymentAttempt, authorization, authorization.getCreatedAt(), eventActor,
                    requestId, fraudEvent.eventId());
        }
        DomainEventWriteResult challengeEvent = null;
        if (createdChallenge != null) {
            UUID challengeCause = authorizationEvent == null
                    ? fraudEvent.eventId() : authorizationEvent.eventId();
            challengeEvent = paymentDomainEventService.authenticationChallengeCreated(
                    paymentIntent, createdChallenge.challenge(), eventActor, requestId, challengeCause);
        }
        if (paymentAttempt.getStatus() != PaymentAttemptStatus.IN_REVIEW) {
            UUID attemptCause = challengeEvent != null ? challengeEvent.eventId()
                    : authorizationEvent != null ? authorizationEvent.eventId() : fraudEvent.eventId();
            paymentDomainEventService.paymentAttemptCompleted(paymentIntent, paymentAttempt,
                    authorization, eventActor, requestId, attemptCause);
        }
        if (authorization != null && authorization.getStatus() == AuthorizationStatus.APPROVED
                && paymentIntent.getCaptureMethod() == CaptureMethod.AUTOMATIC) {
            captureApprovedPaymentIntent(merchantContext, requestId, paymentIntent, authorization,
                    CaptureTrigger.AUTOMATIC_CONFIRM, eventActor,
                    authorizationEvent == null ? fraudEvent.eventId() : authorizationEvent.eventId());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentAttemptResponse.from(paymentAttempt,
                createdChallenge == null ? null : createdChallenge.nextAction()));
    }

    private PersistedFraudLineage persistFraudLineage(MerchantContext merchantContext, PaymentAttempt paymentAttempt,
            MaterializedFeatureSet materializedFeatures, FraudListAssessment fraudListAssessment,
            MerchantPolicyAssessment merchantPolicyAssessment, AuthoredRuleAssessment authoredRuleAssessment,
            ConfirmDecision decision, long latencyMs,
            Instant decisionStartedAt, Instant decisionCompletedAt) {
        // IdempotencyService keeps lineage, attempt mutation, authorization, and completion in one transaction.
        // A later failure rolls these writes back together instead of leaving partial confirm evidence.
        try {
            FraudDecision fraudDecision = fraudDecisionRepository.save(new FraudDecision(merchantContext.merchantId(),
                    paymentAttempt.getId(), decision, latencyMs, decisionStartedAt, decisionCompletedAt,
                    materializedFeatures.freshnessStatus(), materializedFeatures.maxFeatureAgeMs(),
                    paymentAttempt.getTrafficOrigin()));
            FeatureSnapshot featureSnapshot = featureSnapshotRepository.save(new FeatureSnapshot(
                    merchantContext.merchantId(), fraudDecision.getId(),
                    paymentAttempt.getId(), materializedFeatures.snapshot(), materializedFeatures.computedAt(),
                    materializedFeatures.oldestFeatureTimestamp(), materializedFeatures.maxFeatureAgeMs(),
                    materializedFeatures.freshnessStatus(), materializedFeatures.staleFeatureNames(),
                    materializedFeatures.missingFeatureNames(), paymentAttempt.getTrafficOrigin()));
            // Flush the complete local lineage before any processor call. The transaction remains
            // uncommitted, so a downstream failure still rolls everything back atomically.
            for (FeatureValue featureValue : materializedFeatures.values()) {
                featureValueRepository.save(featureValue);
            }
            featureValueRepository.flush();
            for (RuleEvaluation evaluation : decision.ruleEvaluations()) {
                ruleExecutionResultRepository.save(new RuleExecutionResult(merchantContext.merchantId(),
                        fraudDecision.getId(), evaluation, paymentAttempt.getTrafficOrigin()));
            }
            ruleExecutionResultRepository.flush();
            for (FraudListEvaluation evaluation : fraudListAssessment.evaluations()) {
                fraudListEvaluationRecordRepository.save(new FraudListEvaluationRecord(
                        merchantContext.merchantId(), paymentAttempt.getId(), fraudDecision.getId(), evaluation,
                        paymentAttempt.getTrafficOrigin(), decisionCompletedAt));
            }
            fraudListEvaluationRecordRepository.flush();
            merchantRiskPolicyEvaluator.persist(merchantPolicyAssessment, paymentAttempt,
                    fraudDecision.getId(), materializedFeatures, decisionCompletedAt,
                    paymentAttempt.getTrafficOrigin());
            activeAuthoredRuleEvaluator.persist(authoredRuleAssessment, paymentAttempt,
                    fraudDecision.getId(), decisionCompletedAt);
            return new PersistedFraudLineage(fraudDecision, featureSnapshot);
        } catch (RuntimeException exception) {
            featureMaterializationMetrics.recordFailure("PERSISTENCE", "LINEAGE_WRITE_FAILED");
            throw exception;
        }
    }

    private void applyConfirmDecision(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            ConfirmDecision decision) {
        switch (decision.action()) {
            case ALLOW -> {
                paymentAttempt.markReadyForProcessor();
                paymentIntent.markProcessingAfterConfirm();
            }
            case BLOCK -> {
                paymentAttempt.markBlockedByFraud();
                paymentIntent.markBlockedAfterFraudDecision();
            }
            case REQUEST_3DS -> {
                paymentAttempt.markRequiresActionByFraud();
                paymentIntent.markRequiresActionAfterFraudDecision();
            }
            case REVIEW -> {
                paymentAttempt.markInReview();
                paymentIntent.markRequiresReviewAfterFraudDecision();
            }
        }
        log.info("payment_intents.confirm decision applied merchantId={} paymentIntentId={} paymentAttemptId={} "
                        + "action={} reasonCodes={}",
                paymentIntent.getMerchantId(), paymentIntent.getId(), paymentAttempt.getId(), decision.action(),
                decision.reasonCodes());
    }

    private String eventTypeFor(FraudDecisionAction action) {
        return switch (action) {
            case BLOCK -> "payment_intent.blocked";
            case REQUEST_3DS -> "payment_intent.requires_action";
            case REVIEW -> "fraud_review.opened";
            case ALLOW -> throw new IllegalArgumentException("ALLOW has no fraud-only event type");
        };
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
            throw new PublicApiException(PublicApiErrorDescriptor.PROCESSOR_AUTHORIZATION_STATUS_UNSUPPORTED, "Processor authorization status is not supported: " + authorization.getStatus());
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
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE, "PaymentIntent cannot be captured from status " + startingStatus);
        }
        if (paymentIntent.getCaptureMethod() != CaptureMethod.MANUAL) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_CAPTURE_METHOD_INVALID, "Only MANUAL PaymentIntents can use the capture endpoint");
        }
        Authorization authorization = latestApprovedAuthorization(merchantContext, id);
        DomainEventActor eventActor = paymentDomainEventService.merchantActor(merchantContext);
        captureApprovedPaymentIntent(merchantContext, requestId, paymentIntent, authorization,
                CaptureTrigger.MANUAL_API, eventActor, null);
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentIntent));
    }

    private void captureApprovedPaymentIntent(MerchantContext merchantContext, String requestId,
            PaymentIntent paymentIntent, Authorization authorization, CaptureTrigger captureTrigger,
            DomainEventActor eventActor, UUID causationEventId) {
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        if (startingStatus != PaymentIntentStatus.REQUIRES_CAPTURE) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE, "PaymentIntent cannot be captured from status " + startingStatus);
        }
        if (authorizationVoidRepository.existsByAuthorizationIdAndMerchantId(authorization.getId(),
                merchantContext.merchantId())) {
            throw new PublicApiException(PublicApiErrorDescriptor.AUTHORIZATION_VOIDED, "Approved authorization has been voided");
        }
        if (!authorization.getCurrency().equals(paymentIntent.getCurrency())) {
            throw new PublicApiException(PublicApiErrorDescriptor.AUTHORIZATION_CURRENCY_MISMATCH, "Approved authorization currency does not match PaymentIntent");
        }
        if (authorization.getAuthorizedAmount() != paymentIntent.getAmount()) {
            throw new PublicApiException(PublicApiErrorDescriptor.AUTHORIZATION_AMOUNT_MISMATCH, "Approved authorization amount does not match PaymentIntent");
        }
        LedgerService.CaptureLedgerResult ledgerResult = ledgerService.recordCapture(merchantContext.merchantId(),
                paymentIntent.getId(), authorization.getAuthorizedAmount(), authorization.getCurrency());
        paymentIntent.capture();
        webhookDeliveryService.createDeliveriesInCurrentTransaction(
                merchantContext.merchantId(), "payment_intent.captured",
                paymentIntent.getId(), paymentIntent.getTrafficOrigin());
        recordCaptureAuditLog(merchantContext, requestId, startingStatus, paymentIntent, authorization, ledgerResult,
                captureTrigger);
        paymentDomainEventService.captureCreated(paymentIntent, authorization,
                paymentIntent.getUpdatedAt(), eventActor, requestId, causationEventId);
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
        ManualReviewCase reviewCase = null;
        PaymentAttempt canceledReviewAttempt = null;
        DomainEventWriteResult authenticationCancellationEvent = null;
        if (startingStatus == PaymentIntentStatus.REQUIRES_ACTION) {
            authenticationCancellationEvent = authenticationChallengeService.cancelPending(
                    merchantContext, paymentIntent.getId(), requestId);
        }
        if (startingStatus == PaymentIntentStatus.AUTHORIZED
                || startingStatus == PaymentIntentStatus.REQUIRES_CAPTURE) {
            authorization = latestApprovedAuthorization(merchantContext, id);
            authorizationVoid = authorizationVoidRepository.save(new AuthorizationVoid(merchantContext.merchantId(),
                    authorization.getId(), authorization.getPaymentAttemptId(), paymentIntent.getId()));
        } else if (startingStatus == PaymentIntentStatus.REQUIRES_REVIEW) {
            fraudDecisionMetrics.observeReviewCancellation(paymentIntent.getTrafficOrigin());
            ManualReviewCase reviewCandidate = manualReviewCaseRepository
                    .findByPaymentIntentIdAndMerchantIdAndStatus(paymentIntent.getId(),
                            merchantContext.merchantId(), ManualReviewCaseStatus.OPEN)
                    .or(() -> manualReviewCaseRepository.findByPaymentIntentIdAndMerchantIdAndStatus(
                            paymentIntent.getId(), merchantContext.merchantId(), ManualReviewCaseStatus.ASSIGNED))
                    .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_REVIEW_CASE_INACTIVE, "PaymentIntent review case is not active"));
            PaymentAttempt reviewAttempt = paymentAttemptRepository.findByIdAndMerchantIdForUpdate(
                            reviewCandidate.getPaymentAttemptId(), merchantContext.merchantId())
                    .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_REVIEW_ATTEMPT_MISSING, "PaymentIntent review attempt was not found"));
            reviewCase = manualReviewCaseRepository.findByIdAndMerchantIdForUpdate(
                            reviewCandidate.getId(), merchantContext.merchantId())
                    .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_REVIEW_CASE_MISSING, "PaymentIntent review case was not found"));
            reviewAttempt.cancelFromReview();
            canceledReviewAttempt = reviewAttempt;
            reviewCase.cancelByMerchant();
        }
        paymentIntent.cancel();
        webhookDeliveryService.createDeliveriesInCurrentTransaction(
                merchantContext.merchantId(), "payment_intent.canceled",
                paymentIntent.getId(), paymentIntent.getTrafficOrigin());
        if (reviewCase != null) {
            webhookDeliveryService.createDeliveriesInCurrentTransaction(
                    merchantContext.merchantId(), "fraud_review.canceled",
                    reviewCase.getId(), reviewCase.getTrafficOrigin());
            recordReviewCancellationAuditLog(merchantContext, requestId, reviewCase);
        }
        recordCancelAuditLog(merchantContext, requestId, startingStatus, paymentIntent, authorization,
                authorizationVoid);
        DomainEventActor eventActor = paymentDomainEventService.merchantActor(merchantContext);
        DomainEventWriteResult voidEvent = null;
        if (authorization != null && authorizationVoid != null) {
            voidEvent = paymentDomainEventService.authorizationVoided(paymentIntent, authorization,
                    authorizationVoid, eventActor, requestId, null);
        }
        DomainEventWriteResult attemptEvent = null;
        if (canceledReviewAttempt != null) {
            attemptEvent = paymentDomainEventService.paymentAttemptCompleted(paymentIntent,
                    canceledReviewAttempt, null, eventActor, requestId, null);
        }
        UUID cancellationCause = voidEvent != null ? voidEvent.eventId()
                : attemptEvent != null ? attemptEvent.eventId()
                : authenticationCancellationEvent == null ? null
                : authenticationCancellationEvent.eventId();
        paymentDomainEventService.paymentIntentCanceled(paymentIntent, "MERCHANT_REQUESTED",
                eventActor, requestId, cancellationCause);
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentIntent));
    }

    private void recordReviewCancellationAuditLog(MerchantContext merchantContext, String requestId,
            ManualReviewCase reviewCase) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("manualReviewCaseId", reviewCase.getId().toString());
        after.put("paymentIntentId", reviewCase.getPaymentIntentId().toString());
        after.put("paymentAttemptId", reviewCase.getPaymentAttemptId().toString());
        after.put("fraudDecisionId", reviewCase.getFraudDecisionId().toString());
        after.put("status", reviewCase.getStatus().name());
        after.put("cancellationReason", reviewCase.getCancellationReason().name());
        auditLogService.recordMerchantApiAction(merchantContext, requestId, "fraud_review.canceled",
                "manual_review_case", reviewCase.getId(), "Merchant canceled review-pending payment", null, after);
    }

    private Authorization latestApprovedAuthorization(MerchantContext merchantContext, UUID paymentIntentId) {
        return authorizationRepository.findApprovedAuthorizationsForPaymentIntent(merchantContext.merchantId(),
                        paymentIntentId, PaymentAttemptStatus.AUTHORIZED, AuthorizationStatus.APPROVED)
                .stream()
                .findFirst()
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_CAPTURE_AUTHORIZATION_MISSING, "PaymentIntent does not have an approved authorization to capture"));
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
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_FOUND, "PaymentIntent was not found"));
    }

    private PaymentIntent loadPaymentIntentForUpdate(MerchantContext merchantContext, UUID id) {
        return paymentIntentRepository.findByIdAndMerchantIdForUpdate(id, merchantContext.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_FOUND, "PaymentIntent was not found"));
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
            com.thaap.payments.tenant.TrafficOrigin trafficOrigin, Instant createdAt, Instant updatedAt) {
        static PaymentIntentResponse from(PaymentIntent paymentIntent) {
            return new PaymentIntentResponse(paymentIntent.getId(), paymentIntent.getMerchantId(),
                    paymentIntent.getCustomerId(), paymentIntent.getAmount(), paymentIntent.getCurrency(),
                    paymentIntent.getCaptureMethod(), paymentIntent.getStatus(), paymentIntent.getDescription(),
                    paymentIntent.getMetadata(), paymentIntent.getTrafficOrigin(), paymentIntent.getCreatedAt(),
                    paymentIntent.getUpdatedAt());
        }
    }

    public record ConfirmPaymentIntentRequest(@NotNull UUID paymentMethodId, @Valid RiskContextRequest riskContext) {
    }

    private record EmptyMutationRequest() {
    }

    private enum CaptureTrigger {
        AUTOMATIC_CONFIRM,
        MANUAL_API
    }

    private record PersistedFraudLineage(FraudDecision decision, FeatureSnapshot featureSnapshot) {
    }
}
