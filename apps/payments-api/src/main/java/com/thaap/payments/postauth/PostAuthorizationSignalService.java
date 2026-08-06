package com.thaap.payments.postauth;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostAuthorizationSignalService {
    private final MerchantRepository merchantRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final AuthorizationRepository authorizationRepository;
    private final FraudDecisionRepository fraudDecisionRepository;
    private final PostAuthorizationSignalRepository signalRepository;
    private final ManualReviewCaseRepository reviewCaseRepository;
    private final AuditLogService auditLogService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final PostAuthorizationSignalMetrics metrics;
    private final RiskOperationsEventProducer eventProducer;
    private final Clock clock;

    public PostAuthorizationSignalService(MerchantRepository merchantRepository,
            PaymentIntentRepository paymentIntentRepository, AuthorizationRepository authorizationRepository,
            FraudDecisionRepository fraudDecisionRepository,
            PostAuthorizationSignalRepository signalRepository,
            ManualReviewCaseRepository reviewCaseRepository, AuditLogService auditLogService,
            WebhookDeliveryService webhookDeliveryService, PostAuthorizationSignalMetrics metrics,
            RiskOperationsEventProducer eventProducer, Clock clock) {
        this.merchantRepository = merchantRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.authorizationRepository = authorizationRepository;
        this.fraudDecisionRepository = fraudDecisionRepository;
        this.signalRepository = signalRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.auditLogService = auditLogService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.metrics = metrics;
        this.eventProducer = eventProducer;
        this.clock = clock;
    }

    public ResponseEntity<PostAuthorizationSignalController.SignalResponse> create(MerchantContext context,
            PostAuthorizationSignalController.CreateSignalRequest request, String requestId) {
        merchantRepository.findByIdForMoneyMutation(context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.MERCHANT_NOT_FOUND, "Merchant was not found"));
        String fingerprint = fingerprint(request);
        PostAuthorizationSignal duplicate = signalRepository
                .findByMerchantIdAndExternalEventKey(context.merchantId(), request.externalEventKey())
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.getRequestFingerprint().equals(fingerprint)) {
                throw new PublicApiException(PublicApiErrorDescriptor.POST_AUTHORIZATION_EVENT_KEY_CONFLICT, "External post-authorization event key was already used with different content");
            }
            ManualReviewCase existingReview = duplicate.getRecommendation()
                    == PostAuthorizationSignalRecommendation.REVIEW
                            ? reviewCaseRepository.findByPaymentIntentIdAndMerchantIdAndReviewStage(
                                    duplicate.getPaymentIntentId(), context.merchantId(),
                                    ReviewStage.POST_AUTHORIZATION).orElse(null)
                            : null;
            metrics.record(duplicate.getRecommendation(), "DEDUPLICATED");
            return ResponseEntity.ok(PostAuthorizationSignalController.SignalResponse.from(
                    duplicate, existingReview));
        }

        PaymentIntent paymentIntent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(
                        request.paymentIntentId(), context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_FOUND, "PaymentIntent was not found"));
        if (paymentIntent.getStatus() != PaymentIntentStatus.CAPTURED
                && paymentIntent.getStatus() != PaymentIntentStatus.REFUNDED) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_SIGNALABLE, "PaymentIntent cannot receive a post-authorization signal from status "
                            + paymentIntent.getStatus());
        }
        if (paymentIntent.getCaptureMethod() != CaptureMethod.AUTOMATIC) {
            throw new PublicApiException(PublicApiErrorDescriptor.POST_AUTHORIZATION_SIGNAL_REQUIRES_AUTOMATIC_CAPTURE, "Post-authorization signals require an automatically captured PaymentIntent");
        }
        Instant now = clock.instant();
        if (request.evidenceOccurredAt().isAfter(request.receivedAt())
                || request.receivedAt().isAfter(now)) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIGNAL_TIME_ORDER_INVALID, "Signal time order must be evidenceOccurredAt <= receivedAt <= server time");
        }
        Authorization authorization = authorizationRepository.findApprovedAuthorizationsForPaymentIntent(
                        context.merchantId(), paymentIntent.getId(), PaymentAttemptStatus.AUTHORIZED,
                        AuthorizationStatus.APPROVED).stream().findFirst()
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_AUTHORIZATION_EVIDENCE_MISSING, "PaymentIntent does not have approved authorization evidence"));
        if (authorization.getAuthorizedAmount() != paymentIntent.getAmount()
                || !authorization.getCurrency().equals(paymentIntent.getCurrency())) {
            throw new PublicApiException(PublicApiErrorDescriptor.CAPTURE_AUTHORIZATION_MISMATCH, "Authorization does not match captured PaymentIntent");
        }
        if (request.evidenceOccurredAt().isBefore(authorization.getCreatedAt())) {
            throw new PublicApiException(PublicApiErrorDescriptor.POST_AUTHORIZATION_SIGNAL_EVIDENCE_PREDATES_AUTHORIZATION, "Post-authorization signal evidence cannot predate authorization");
        }
        FraudDecision decision = fraudDecisionRepository.findByMerchantIdAndPaymentAttemptId(
                        context.merchantId(), authorization.getPaymentAttemptId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_ATTEMPT_FRAUD_LINEAGE_MISSING, "PaymentAttempt does not have fraud decision lineage"));
        PostAuthorizationSignal signal = signalRepository.saveAndFlush(new PostAuthorizationSignal(
                context.merchantId(), request.externalEventKey(), fingerprint, paymentIntent.getId(),
                authorization.getPaymentAttemptId(), authorization.getId(), decision.getId(),
                request.recommendation(), request.reasonCode(), paymentIntent.getTrafficOrigin(),
                request.evidenceOccurredAt(), request.receivedAt(), now));
        eventProducer.postAuthorizationSignalRecorded(context, paymentIntent.getId(), now,
                requestId, "SIMULATOR_POST_AUTHORIZATION", request.recommendation().name(),
                java.util.List.of(request.reasonCode().name()), paymentIntent.getTrafficOrigin());
        ManualReviewCase reviewCase = null;
        if (request.recommendation() == PostAuthorizationSignalRecommendation.REVIEW) {
            reviewCase = reviewCaseRepository.findByPaymentIntentIdAndMerchantIdAndReviewStage(
                    paymentIntent.getId(), context.merchantId(), ReviewStage.POST_AUTHORIZATION).orElse(null);
            if (reviewCase == null) {
                reviewCase = reviewCaseRepository.saveAndFlush(new ManualReviewCase(signal, decision));
                eventProducer.manualReviewOpenedBySystem(context.merchantId(), paymentIntent.getId(),
                        now, requestId, paymentIntent.getTrafficOrigin(), "POST_AUTHORIZATION",
                        decision.getReasonCodes().stream()
                                .map(Enum::name).toList());
                webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                        "fraud_review.opened", reviewCase.getId(), paymentIntent.getTrafficOrigin());
            }
        }
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "post_authorization_signal.created", signal.getId(), paymentIntent.getTrafficOrigin());
        audit(context, requestId, signal, reviewCase);
        metrics.record(request.recommendation(), "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostAuthorizationSignalController.SignalResponse.from(signal, reviewCase));
    }

    private void audit(MerchantContext context, String requestId, PostAuthorizationSignal signal,
            ManualReviewCase reviewCase) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("signalId", signal.getId().toString());
        after.put("recommendation", signal.getRecommendation().name());
        after.put("reasonCode", signal.getReasonCode().name());
        after.put("sourceAttestation", signal.getSourceAttestation());
        after.put("actionTaken", reviewCase == null ? "NONE"
                : reviewCase.getSourcePostAuthorizationSignalId().equals(signal.getId())
                        ? "REVIEW_OPENED" : "REVIEW_ALREADY_OPEN");
        if (reviewCase != null) {
            after.put("reviewCaseId", reviewCase.getId().toString());
        }
        auditLogService.recordMerchantApiAction(context, requestId, "post_authorization_signal.created",
                "post_authorization_signal", signal.getId(),
                "Simulator post-authorization recommendation recorded without automatic money action",
                null, after);
    }

    private String fingerprint(PostAuthorizationSignalController.CreateSignalRequest request) {
        return SecretHasher.sha256(request.paymentIntentId() + "|" + request.recommendation() + "|"
                + request.reasonCode() + "|" + request.evidenceOccurredAt() + "|" + request.receivedAt());
    }
}
