package com.thaap.payments.review;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authentication.AuthenticationChallengeService;
import com.thaap.payments.authentication.AuthenticationChallengeSource;
import com.thaap.payments.authentication.AuthenticationNextAction;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.FullRefundService;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.payment.PaymentDomainEventService;
import com.thaap.payments.payment.Refund;
import com.thaap.payments.payment.RefundReasonCode;
import com.thaap.payments.payment.RefundRepository;
import com.thaap.payments.postauth.PostAuthorizationSignal;
import com.thaap.payments.postauth.PostAuthorizationSignalRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.processor.ProcessorAuthorizationResult;
import com.thaap.payments.risk.RiskOperationsMetrics;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewOperationsService {

    private final RiskOperatorRepository operatorRepository;
    private final ManualReviewCaseRepository reviewCaseRepository;
    private final ReviewAssignmentEventRepository assignmentEventRepository;
    private final ReviewAnnotationRepository annotationRepository;
    private final ReviewResolutionRepository resolutionRepository;
    private final FraudLabelRepository fraudLabelRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final ProcessorAdapter processorAdapter;
    private final AuthorizationRepository authorizationRepository;
    private final LedgerService ledgerService;
    private final AuditLogService auditLogService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final RiskOperationsMetrics metrics;
    private final Clock clock;
    private final AuthenticationChallengeService authenticationChallengeService;
    private final RefundRepository refundRepository;
    private final FullRefundService fullRefundService;
    private final PostAuthorizationSignalRepository postAuthorizationSignalRepository;
    private final ReviewRefundActionRepository reviewRefundActionRepository;
    private final PaymentDomainEventService paymentDomainEventService;
    private final RiskOperationsEventProducer riskOperationsEventProducer;

    public ReviewOperationsService(RiskOperatorRepository operatorRepository,
            ManualReviewCaseRepository reviewCaseRepository,
            ReviewAssignmentEventRepository assignmentEventRepository,
            ReviewAnnotationRepository annotationRepository,
            ReviewResolutionRepository resolutionRepository, FraudLabelRepository fraudLabelRepository,
            PaymentIntentRepository paymentIntentRepository, PaymentAttemptRepository paymentAttemptRepository,
            PaymentMethodRepository paymentMethodRepository, ProcessorAdapter processorAdapter,
            AuthorizationRepository authorizationRepository, LedgerService ledgerService,
            AuditLogService auditLogService, WebhookDeliveryService webhookDeliveryService,
            RiskOperationsMetrics metrics, Clock clock,
            AuthenticationChallengeService authenticationChallengeService,
            RefundRepository refundRepository, FullRefundService fullRefundService,
            PostAuthorizationSignalRepository postAuthorizationSignalRepository,
            ReviewRefundActionRepository reviewRefundActionRepository,
            PaymentDomainEventService paymentDomainEventService,
            RiskOperationsEventProducer riskOperationsEventProducer) {
        this.operatorRepository = operatorRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.assignmentEventRepository = assignmentEventRepository;
        this.annotationRepository = annotationRepository;
        this.resolutionRepository = resolutionRepository;
        this.fraudLabelRepository = fraudLabelRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.processorAdapter = processorAdapter;
        this.authorizationRepository = authorizationRepository;
        this.ledgerService = ledgerService;
        this.auditLogService = auditLogService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.metrics = metrics;
        this.clock = clock;
        this.authenticationChallengeService = authenticationChallengeService;
        this.refundRepository = refundRepository;
        this.fullRefundService = fullRefundService;
        this.postAuthorizationSignalRepository = postAuthorizationSignalRepository;
        this.reviewRefundActionRepository = reviewRefundActionRepository;
        this.paymentDomainEventService = paymentDomainEventService;
        this.riskOperationsEventProducer = riskOperationsEventProducer;
    }

    public ManualReviewCase assign(RiskOperatorContext context, UUID reviewCaseId,
            UUID requestedOperatorId, long expectedVersion, String requestId) {
        RiskOperator actor = requireMutableActor(context);
        requireRole(actor, RiskOperatorRole.ANALYST, RiskOperatorRole.RISK_MANAGER);
        UUID targetOperatorId = requestedOperatorId == null ? actor.getId() : requestedOperatorId;
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER && !actor.getId().equals(targetOperatorId)) {
            forbidden();
        }
        RiskOperator target = operatorRepository.findByIdAndMerchantIdForUpdate(targetOperatorId, context.merchantId())
                .filter(RiskOperator::isActive)
                .filter(operator -> operator.getRole() != RiskOperatorRole.VIEWER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Review or target operator was not found"));
        ManualReviewCase reviewCase = lockReview(context, reviewCaseId);
        if (reviewCase.getStatus() == ManualReviewCaseStatus.ASSIGNED
                && actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            forbidden();
        }
        ManualReviewCase.ReviewAssignmentTransition transition = reviewCase.assign(
                target.getId(), expectedVersion, clock.instant());
        if (transition != null) {
            assignmentEventRepository.save(new ReviewAssignmentEvent(context.merchantId(), reviewCaseId,
                    transition, actor.getId(), clock.instant()));
            riskOperationsEventProducer.manualReviewAssigned(context,
                    reviewCase.getPaymentIntentId(), reviewCase.getUpdatedAt(), requestId,
                    transition.assignmentVersion(), transition.toOperatorId(),
                    reviewCase.getTrafficOrigin());
            auditLogService.recordRiskOperatorAction(context, requestId, "review.assigned",
                    "manual_review_case", reviewCaseId, "Review assignment changed", null,
                    reviewState(reviewCase));
            webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                    "review.assigned", reviewCaseId, reviewCase.getTrafficOrigin());
        }
        metrics.recordCommitted("assign", transition == null ? "unchanged" : transition.eventType().name());
        return reviewCase;
    }

    public ManualReviewCase release(RiskOperatorContext context, UUID reviewCaseId,
            long expectedVersion, String requestId) {
        RiskOperator actor = requireMutableActor(context);
        requireRole(actor, RiskOperatorRole.ANALYST, RiskOperatorRole.RISK_MANAGER);
        ManualReviewCase reviewCase = lockReview(context, reviewCaseId);
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER
                && !actor.getId().equals(reviewCase.getAssignedOperatorId())) {
            forbidden();
        }
        ManualReviewCase.ReviewAssignmentTransition transition = reviewCase.release(
                expectedVersion, clock.instant());
        assignmentEventRepository.save(new ReviewAssignmentEvent(context.merchantId(), reviewCaseId,
                transition, actor.getId(), clock.instant()));
        auditLogService.recordRiskOperatorAction(context, requestId, "review.released",
                "manual_review_case", reviewCaseId, "Review assignment released", null,
                reviewState(reviewCase));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "review.released", reviewCaseId, reviewCase.getTrafficOrigin());
        metrics.recordCommitted("release", "RELEASED");
        return reviewCase;
    }

    public ReviewAnnotation annotate(RiskOperatorContext context, UUID reviewCaseId,
            ReviewAnnotationCode code, String requestId) {
        RiskOperator actor = requireMutableActor(context);
        requireRole(actor, RiskOperatorRole.ANALYST, RiskOperatorRole.RISK_MANAGER);
        ManualReviewCase reviewCase = lockReview(context, reviewCaseId);
        if (reviewCase.getStatus() != ManualReviewCaseStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Review must be assigned before annotation");
        }
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER
                && !actor.getId().equals(reviewCase.getAssignedOperatorId())) {
            forbidden();
        }
        ReviewAnnotation annotation = annotationRepository.save(new ReviewAnnotation(
                context.merchantId(), reviewCaseId, actor.getId(), code, clock.instant()));
        auditLogService.recordRiskOperatorAction(context, requestId, "review.annotation.created",
                "review_annotation", annotation.getId(), "Structured review annotation added",
                null, Map.of("reviewCaseId", reviewCaseId.toString(), "code", code.name()));
        metrics.recordCommitted("annotate", code.name());
        return annotation;
    }

    public ResolutionResult resolve(RiskOperatorContext context, UUID reviewCaseId,
            ReviewResolutionOutcome outcome, ReviewClassification classification, FraudType fraudType,
            ReviewResolutionReasonCode reasonCode, Instant evidenceOccurredAt, String requestId) {
        RiskOperator actor = requireMutableActor(context);
        DomainEventActor paymentEventActor = paymentDomainEventService.riskOperatorActor(context);
        requireRole(actor, RiskOperatorRole.ANALYST, RiskOperatorRole.RISK_MANAGER);
        ManualReviewCaseRepository.PaymentLineage candidate = reviewCaseRepository
                .findPaymentLineageByIdAndMerchantId(reviewCaseId, context.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ManualReviewCase was not found"));
        PaymentIntent paymentIntent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(
                        candidate.getPaymentIntentId(), context.merchantId())
                .orElseThrow(() -> lineageConflict("PaymentIntent"));
        PaymentAttempt paymentAttempt = paymentAttemptRepository.findByIdAndMerchantIdForUpdate(
                        candidate.getPaymentAttemptId(), context.merchantId())
                .orElseThrow(() -> lineageConflict("PaymentAttempt"));
        ManualReviewCase reviewCase = lockReview(context, reviewCaseId);
        validateResolutionSemantics(reviewCase, actor, outcome, classification,
                fraudType, reasonCode, evidenceOccurredAt);
        if (reviewCase.getStatus() == ManualReviewCaseStatus.RESOLVED) {
            ReviewResolution committedResolution = resolutionRepository
                    .findByMerchantIdAndManualReviewCaseId(context.merchantId(), reviewCaseId)
                    .orElseThrow(() -> lineageConflict("resolution"));
            FraudLabel committedLabel = fraudLabelRepository
                    .findByMerchantIdAndSourceReviewResolutionId(
                            context.merchantId(), committedResolution.getId())
                    .orElse(null);
            Authorization committedAuthorization = authorizationRepository
                    .findByPaymentAttemptIdAndMerchantId(
                            committedResolution.getPaymentAttemptId(), context.merchantId())
                    .orElse(null);
            ReviewRefundAction committedRefundAction = reviewRefundActionRepository
                    .findByMerchantIdAndReviewResolutionId(context.merchantId(), committedResolution.getId())
                    .orElse(null);
            Refund committedRefund = committedRefundAction == null ? null
                    : refundRepository.findByIdAndMerchantId(committedRefundAction.getRefundId(),
                            context.merchantId()).orElseThrow(() -> lineageConflict("refund action"));
            metrics.recordCommitted("resolve", "COMMITTED_TERMINAL_REPLAY");
            return new ResolutionResult(committedResolution, committedLabel, committedRefund,
                    committedRefundAction, committedAuthorization, paymentIntent.getStatus(), paymentAttempt.getStatus(),
                    authenticationChallengeService.nextActionForAttempt(
                            context.merchantId(), committedResolution.getPaymentAttemptId()));
        }
        if (reviewCase.getReviewStage() == ReviewStage.PRE_AUTHORIZATION
                && (paymentIntent.getStatus() != PaymentIntentStatus.REQUIRES_REVIEW
                    || paymentAttempt.getStatus() != PaymentAttemptStatus.IN_REVIEW)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Review payment lineage is no longer resolvable");
        }
        if (reviewCase.getReviewStage() == ReviewStage.POST_AUTHORIZATION
                && paymentIntent.getStatus() != PaymentIntentStatus.CAPTURED
                && paymentIntent.getStatus() != PaymentIntentStatus.REFUNDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Post-authorization review payment lineage is no longer resolvable");
        }
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER
                && !actor.getId().equals(reviewCase.getAssignedOperatorId())) {
            forbidden();
        }
        metrics.recordCommitted("resolve", outcome.name());
        Instant now = clock.instant();
        ReviewResolution resolution = resolutionRepository.save(new ReviewResolution(reviewCase,
                actor.getId(), actor.getRole(), outcome, classification, fraudType, reasonCode, now));
        FraudLabel label = null;
        Instant labelEvidenceOccurredAt = evidenceOccurredAt;
        if (reviewCase.getReviewStage() == ReviewStage.POST_AUTHORIZATION) {
            PostAuthorizationSignal signal = postAuthorizationSignalRepository.findByIdAndMerchantId(
                            reviewCase.getSourcePostAuthorizationSignalId(), context.merchantId())
                    .orElseThrow(() -> lineageConflict("post-authorization signal"));
            labelEvidenceOccurredAt = signal.getEvidenceOccurredAt();
        }
        if (classification != ReviewClassification.INCONCLUSIVE) {
            label = fraudLabelRepository.save(new FraudLabel(reviewCase, resolution,
                    actor.getId(), labelEvidenceOccurredAt, now));
        }
        riskOperationsEventProducer.manualReviewResolved(context, paymentIntent.getId(), now,
                requestId, outcome.name(), fraudType == null ? null : fraudType.name(),
                reviewCase.getTrafficOrigin());
        if (label != null) {
            riskOperationsEventProducer.fraudLabelRecorded(context, paymentIntent.getId(), now,
                    requestId, label.getLabelValue().name(), label.getSource(), label.getAvailableAt(),
                    label.getFraudType() == null ? null : label.getFraudType().name(),
                    label.getTrafficOrigin());
        }
        Authorization authorization = null;
        Refund refund = null;
        ReviewRefundAction refundAction = null;
        AuthenticationNextAction nextAction = null;
        if (reviewCase.getReviewStage() == ReviewStage.PRE_AUTHORIZATION
                && outcome == ReviewResolutionOutcome.APPROVE) {
            resolutionRepository.flush();
            if (label != null) {
                fraudLabelRepository.flush();
            }
            paymentAttempt.markReadyForProcessorAfterReview();
            paymentIntent.markProcessingAfterReviewApproval();
            PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndMerchantId(
                            paymentAttempt.getPaymentMethodId(), context.merchantId())
                    .orElseThrow(() -> lineageConflict("PaymentMethod"));
            ProcessorAuthorizationResult processorResult = processorAdapter.authorize(
                    new ProcessorAuthorizationRequest(context.merchantId(), paymentIntent.getId(),
                            paymentAttempt.getId(), paymentMethod.getId(), paymentIntent.getAmount(),
                            paymentIntent.getCurrency(), paymentMethod.getCardBin(), paymentMethod.getCardLast4()));
            authorization = authorizationRepository.save(new Authorization(context.merchantId(),
                    paymentAttempt.getId(), processorResult.processor(), processorResult.processorReferenceId(),
                    processorResult.responseCode(), processorResult.networkStatus(),
                    processorResult.requestedAmount(), processorResult.authorizedAmount(),
                    processorResult.currency(), processorResult.status(), processorResult.cvcCheck(),
                    processorResult.avsLine1Check(), processorResult.avsPostalCodeCheck(),
                    processorResult.threeDsResult(), processorResult.simulatorOutcomeCode()));
            authorizationRepository.flush();
            applyProcessorResult(paymentIntent, paymentAttempt, authorization);
            DomainEventWriteResult authorizationEvent = null;
            if (authorization.getStatus() == AuthorizationStatus.APPROVED) {
                authorizationEvent = paymentDomainEventService.authorizationCreated(paymentIntent,
                        paymentAttempt, authorization, authorization.getCreatedAt(), paymentEventActor,
                        requestId, null);
            }
            DomainEventWriteResult challengeEvent = null;
            if (authorization.getStatus() == AuthorizationStatus.REQUIRES_ACTION) {
                AuthenticationChallengeService.CreatedChallenge createdChallenge =
                        authenticationChallengeService.create(
                                context.merchantId(), paymentIntent.getId(), paymentAttempt.getId(),
                                resolution.getFraudDecisionId(), authorization.getId(),
                                AuthenticationChallengeSource.PROCESSOR, reviewCase.getTrafficOrigin());
                nextAction = createdChallenge.nextAction();
                challengeEvent = paymentDomainEventService.authenticationChallengeCreated(
                        paymentIntent, createdChallenge.challenge(), paymentEventActor, requestId, null);
            }
            UUID attemptCause = challengeEvent != null ? challengeEvent.eventId()
                    : authorizationEvent == null ? null : authorizationEvent.eventId();
            paymentDomainEventService.paymentAttemptCompleted(paymentIntent, paymentAttempt,
                    authorization, paymentEventActor, requestId, attemptCause);
            if (authorization.getStatus() == AuthorizationStatus.APPROVED
                    && paymentIntent.getCaptureMethod() == CaptureMethod.AUTOMATIC) {
                captureApprovedReviewPayment(context, requestId, paymentIntent, authorization,
                        paymentEventActor, authorizationEvent == null ? null : authorizationEvent.eventId());
            }
        } else if (reviewCase.getReviewStage() == ReviewStage.PRE_AUTHORIZATION) {
            paymentAttempt.cancelFromReview();
            paymentIntent.cancel();
            DomainEventWriteResult attemptEvent = paymentDomainEventService.paymentAttemptCompleted(
                    paymentIntent, paymentAttempt, null, paymentEventActor, requestId, null);
            paymentDomainEventService.paymentIntentCanceled(paymentIntent, reasonCode.name(),
                    paymentEventActor, requestId, attemptEvent.eventId());
        } else if (outcome == ReviewResolutionOutcome.REFUND
                || outcome == ReviewResolutionOutcome.REFUND_AND_REPORT_FRAUD) {
            resolutionRepository.flush();
            RefundReasonCode refundReason = outcome == ReviewResolutionOutcome.REFUND
                    ? RefundReasonCode.RISK_REVIEW_REMEDIATION
                    : RefundReasonCode.CONFIRMED_FRAUD_CUSTOMER_PROTECTION;
            FullRefundService.Result refundResult = fullRefundService.refundLocked(context.merchantId(),
                    paymentIntent, refundReason, resolution.getId(), true);
            refund = refundResult.refund();
            refundAction = reviewRefundActionRepository.saveAndFlush(new ReviewRefundAction(
                    resolution, refund, refundReason, refundResult.reused(), now));
            if (!refundResult.reused()) {
                paymentDomainEventService.refundCreated(paymentIntent, refund,
                        paymentEventActor, requestId, null);
            }
        }
        reviewCase.resolve(now);
        auditLogService.recordRiskOperatorAction(context, requestId, "review.resolved",
                "review_resolution", resolution.getId(), reviewCase.getReviewStage().name()
                        + " review resolved",
                null, resolutionState(resolution, label, refund, authorization, paymentIntent, paymentAttempt));
        if (reviewCase.getReviewStage() == ReviewStage.PRE_AUTHORIZATION
                && outcome != ReviewResolutionOutcome.APPROVE) {
            webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                    "payment_intent.canceled", paymentIntent.getId(), reviewCase.getTrafficOrigin());
        } else if (authorization != null && authorization.getStatus() == AuthorizationStatus.REQUIRES_ACTION) {
            webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                    "payment_intent.requires_action", paymentIntent.getId(), reviewCase.getTrafficOrigin());
        }
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "review.resolved", reviewCaseId, reviewCase.getTrafficOrigin());
        if (label != null) {
            auditLogService.recordRiskOperatorAction(context, requestId, "fraud_label.created",
                    "fraud_label", label.getId(), "Confirmed fraud label created",
                    null, Map.of("reviewResolutionId", resolution.getId().toString(),
                            "labelValue", label.getLabelValue().name(),
                            "fraudType", label.getFraudType() == null
                                    ? "NONE" : label.getFraudType().name()));
            webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                    "fraud_label.created", label.getId(), reviewCase.getTrafficOrigin());
        }
        return new ResolutionResult(resolution, label, refund, refundAction, authorization,
                paymentIntent.getStatus(), paymentAttempt.getStatus(), nextAction);
    }

    private RiskOperator requireMutableActor(RiskOperatorContext context) {
        return operatorRepository.findByIdAndMerchantIdForUpdate(context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Risk resource was not found"));
    }

    private ManualReviewCase lockReview(RiskOperatorContext context, UUID reviewCaseId) {
        return reviewCaseRepository.findByIdAndMerchantIdForUpdate(reviewCaseId, context.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ManualReviewCase was not found"));
    }

    private void requireRole(RiskOperator operator, RiskOperatorRole... roles) {
        for (RiskOperatorRole role : roles) {
            if (operator.getRole() == role) {
                return;
            }
        }
        forbidden();
    }

    private void forbidden() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk resource was not found");
    }

    private ResponseStatusException lineageConflict(String resource) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Review " + resource + " lineage was not found");
    }

    private void validateResolutionSemantics(ManualReviewCase reviewCase, RiskOperator actor,
            ReviewResolutionOutcome outcome,
            ReviewClassification classification, FraudType fraudType,
            ReviewResolutionReasonCode reasonCode, Instant evidenceOccurredAt) {
        if (reviewCase.getReviewStage() == ReviewStage.POST_AUTHORIZATION) {
            if ((outcome == ReviewResolutionOutcome.REFUND
                    || outcome == ReviewResolutionOutcome.REFUND_AND_REPORT_FRAUD)
                    && actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
                forbidden();
            }
            if (outcome == ReviewResolutionOutcome.NO_ACTION
                    && ((classification != ReviewClassification.INCONCLUSIVE
                            && classification != ReviewClassification.CONFIRMED_LEGITIMATE)
                        || fraudType != null
                        || reasonCode != ReviewResolutionReasonCode.POST_AUTHORIZATION_NO_ACTION)) {
                badRequest("NO_ACTION requires INCONCLUSIVE or CONFIRMED_LEGITIMATE and no fraud type");
            }
            if (outcome == ReviewResolutionOutcome.REFUND
                    && (classification != ReviewClassification.INCONCLUSIVE || fraudType != null
                        || reasonCode != ReviewResolutionReasonCode.POST_AUTHORIZATION_REFUND)) {
                badRequest("REFUND requires INCONCLUSIVE and POST_AUTHORIZATION_REFUND reason");
            }
            if (outcome == ReviewResolutionOutcome.REFUND_AND_REPORT_FRAUD
                    && (classification != ReviewClassification.CONFIRMED_FRAUD || fraudType == null
                        || !reasonMatchesFraudType(fraudType, reasonCode))) {
                badRequest("REFUND_AND_REPORT_FRAUD requires matching confirmed fraud type and reason");
            }
            if (outcome != ReviewResolutionOutcome.NO_ACTION
                    && outcome != ReviewResolutionOutcome.REFUND
                    && outcome != ReviewResolutionOutcome.REFUND_AND_REPORT_FRAUD) {
                badRequest("Outcome is incompatible with POST_AUTHORIZATION review stage");
            }
            PostAuthorizationSignal signal = postAuthorizationSignalRepository.findByIdAndMerchantId(
                            reviewCase.getSourcePostAuthorizationSignalId(), reviewCase.getMerchantId())
                    .orElseThrow(() -> lineageConflict("post-authorization signal"));
            if (!signal.getEvidenceOccurredAt().equals(evidenceOccurredAt)) {
                badRequest("evidenceOccurredAt must equal the immutable source signal evidence time");
            }
        } else if (outcome == ReviewResolutionOutcome.NO_ACTION
                || outcome == ReviewResolutionOutcome.REFUND
                || outcome == ReviewResolutionOutcome.REFUND_AND_REPORT_FRAUD) {
            badRequest("Outcome is incompatible with PRE_AUTHORIZATION review stage");
        }
        if (outcome == ReviewResolutionOutcome.CANCEL
                && (classification != ReviewClassification.INCONCLUSIVE || fraudType != null
                    || reasonCode != ReviewResolutionReasonCode.RISK_TOO_HIGH)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CANCEL requires INCONCLUSIVE classification, no fraudType, and RISK_TOO_HIGH reason");
        }
        if (outcome == ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD
                && (classification != ReviewClassification.CONFIRMED_FRAUD || fraudType == null
                    || !reasonMatchesFraudType(fraudType, reasonCode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CANCEL_AND_REPORT_FRAUD requires matching confirmed fraud type and reason");
        }
        if (outcome == ReviewResolutionOutcome.APPROVE
                && ((classification != ReviewClassification.INCONCLUSIVE
                        && classification != ReviewClassification.CONFIRMED_LEGITIMATE)
                    || fraudType != null
                    || reasonCode != ReviewResolutionReasonCode.MANUAL_REVIEW_APPROVED)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "APPROVE requires INCONCLUSIVE or CONFIRMED_LEGITIMATE, no fraudType, "
                            + "and MANUAL_REVIEW_APPROVED reason");
        }
        if (evidenceOccurredAt == null || evidenceOccurredAt.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "evidenceOccurredAt must not be in the future");
        }
    }

    private void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean reasonMatchesFraudType(FraudType fraudType, ReviewResolutionReasonCode reasonCode) {
        return switch (fraudType) {
            case STOLEN_PAYMENT_CREDENTIAL ->
                reasonCode == ReviewResolutionReasonCode.IDENTITY_MISMATCH_CONFIRMED;
            case ACCOUNT_TAKEOVER ->
                reasonCode == ReviewResolutionReasonCode.ACCOUNT_TAKEOVER_CONFIRMED;
            case CARD_TESTING ->
                reasonCode == ReviewResolutionReasonCode.CARD_TESTING_CONFIRMED;
            case FIRST_PARTY_MISUSE ->
                reasonCode == ReviewResolutionReasonCode.FIRST_PARTY_MISUSE_CONFIRMED;
            case OTHER_CONFIRMED_FRAUD ->
                reasonCode == ReviewResolutionReasonCode.OTHER_FRAUD_CONFIRMED;
        };
    }

    private Map<String, Object> reviewState(ManualReviewCase reviewCase) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", reviewCase.getStatus().name());
        state.put("assignmentVersion", reviewCase.getAssignmentVersion());
        if (reviewCase.getAssignedOperatorId() != null) {
            state.put("assignedOperatorId", reviewCase.getAssignedOperatorId().toString());
        }
        return state;
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
    }

    private void captureApprovedReviewPayment(RiskOperatorContext context, String requestId,
            PaymentIntent paymentIntent, Authorization authorization, DomainEventActor eventActor,
            UUID causationEventId) {
        LedgerService.CaptureLedgerResult ledgerResult = ledgerService.recordCapture(
                context.merchantId(), paymentIntent.getId(), authorization.getAuthorizedAmount(),
                authorization.getCurrency());
        paymentIntent.capture();
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "payment_intent.captured", paymentIntent.getId(), paymentIntent.getTrafficOrigin());
        auditLogService.recordRiskOperatorAction(context, requestId, "payment_intent.captured",
                "payment_intent", paymentIntent.getId(), "Review-approved PaymentIntent captured",
                null, Map.of("status", paymentIntent.getStatus().name(),
                        "captureAmount", authorization.getAuthorizedAmount(),
                        "currency", authorization.getCurrency(),
                        "authorizationId", authorization.getId().toString(),
                        "transactionGroupId", ledgerResult.transactionGroupId().toString(),
                        "ledgerEntryIds", ledgerResult.entries().stream()
                                .map(LedgerEntry::getId).map(UUID::toString).toList()));
        paymentDomainEventService.captureCreated(paymentIntent, authorization,
                paymentIntent.getUpdatedAt(), eventActor, requestId, causationEventId);
    }

    private Map<String, Object> resolutionState(ReviewResolution resolution, FraudLabel label, Refund refund,
            Authorization authorization, PaymentIntent paymentIntent, PaymentAttempt paymentAttempt) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("reviewCaseId", resolution.getManualReviewCaseId().toString());
        state.put("outcome", resolution.getOutcome().name());
        state.put("classification", resolution.getClassification().name());
        state.put("labelCreated", label != null);
        state.put("paymentIntentStatus", paymentIntent.getStatus().name());
        state.put("paymentAttemptStatus", paymentAttempt.getStatus().name());
        if (refund != null) {
            state.put("refundId", refund.getId().toString());
            state.put("refundReasonCode", refund.getReasonCode().name());
            state.put("refundReused", !resolution.getId().equals(refund.getSourceReviewResolutionId()));
        }
        if (authorization != null) {
            state.put("authorizationId", authorization.getId().toString());
            state.put("processorOutcome", authorization.getStatus().name());
        }
        return state;
    }

    public record ResolutionResult(ReviewResolution resolution, FraudLabel label, Refund refund,
            ReviewRefundAction refundAction, Authorization authorization, PaymentIntentStatus paymentIntentStatus,
            PaymentAttemptStatus paymentAttemptStatus, AuthenticationNextAction nextAction) {
    }
}
