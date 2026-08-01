package com.thaap.payments.authentication;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.payment.PaymentDomainEventService;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.processor.ProcessorAuthorizationResult;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticationChallengeService {

    private static final String SECRET_DOMAIN = "authentication-challenge-secret-v1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthenticationChallengeRepository challengeRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final AuthorizationRepository authorizationRepository;
    private final ProcessorAdapter processorAdapter;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final LedgerService ledgerService;
    private final AuditLogService auditLogService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final AuthenticationMetrics authenticationMetrics;
    private final Clock clock;
    private final Duration challengeTtl;
    private final PaymentDomainEventService paymentDomainEventService;

    public AuthenticationChallengeService(AuthenticationChallengeRepository challengeRepository,
            PaymentIntentRepository paymentIntentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentMethodRepository paymentMethodRepository,
            AuthorizationRepository authorizationRepository, ProcessorAdapter processorAdapter,
            SensitiveValueHasher sensitiveValueHasher, LedgerService ledgerService,
            AuditLogService auditLogService, WebhookDeliveryService webhookDeliveryService,
            AuthenticationMetrics authenticationMetrics, Clock clock,
            PaymentDomainEventService paymentDomainEventService,
            @Value("${thaap.authentication.challenge-ttl:5m}") Duration challengeTtl) {
        if (challengeTtl.isNegative() || challengeTtl.isZero() || challengeTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("Authentication challenge TTL must be in (0, 1h]");
        }
        this.challengeRepository = challengeRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.authorizationRepository = authorizationRepository;
        this.processorAdapter = processorAdapter;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.ledgerService = ledgerService;
        this.auditLogService = auditLogService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.authenticationMetrics = authenticationMetrics;
        this.clock = clock;
        this.challengeTtl = challengeTtl;
        this.paymentDomainEventService = paymentDomainEventService;
    }

    public CreatedChallenge create(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, UUID authorizationId, AuthenticationChallengeSource source,
            TrafficOrigin trafficOrigin) {
        if ((source == AuthenticationChallengeSource.PROCESSOR) != (authorizationId != null)) {
            throw new IllegalArgumentException("Processor challenge requires its pending authorization");
        }
        String secret = newSecret();
        String prefix = secret.substring(0, 17);
        Instant now = clock.instant();
        AuthenticationChallenge challenge = challengeRepository.saveAndFlush(new AuthenticationChallenge(
                merchantId, paymentIntentId, paymentAttemptId, fraudDecisionId, authorizationId,
                source, prefix, sha256(secret), sensitiveValueHasher.hmacSha256(SECRET_DOMAIN, secret),
                sensitiveValueHasher.currentKeyVersion(), now.plus(challengeTtl), trafficOrigin, now));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(merchantId,
                "authentication_challenge.created", challenge.getId(), trafficOrigin);
        authenticationMetrics.recordCreation(source);
        return new CreatedChallenge(challenge, new AuthenticationNextAction(
                challenge.getId(), source, challenge.getExpiresAt(),
                completionEndpoint(paymentAttemptId, challenge.getId()), secret));
    }

    public CompletionResult complete(MerchantContext context, UUID paymentAttemptId, UUID challengeId,
            CompletionOutcome outcome, String clientSecret, String requestId) {
        AuthenticationChallengeRepository.ChallengeLineage lineage = challengeRepository
                .findLineageByIdAndMerchantId(challengeId, context.merchantId())
                .orElseThrow(this::notFound);
        if (!lineage.getPaymentAttemptId().equals(paymentAttemptId)) {
            throw notFound();
        }
        PaymentIntent paymentIntent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(
                        lineage.getPaymentIntentId(), context.merchantId())
                .orElseThrow(this::notFound);
        PaymentAttempt paymentAttempt = paymentAttemptRepository.findByIdAndMerchantIdForUpdate(
                        paymentAttemptId, context.merchantId())
                .orElseThrow(this::notFound);
        AuthenticationChallenge challenge = challengeRepository.findByIdAndMerchantIdForUpdate(
                        challengeId, context.merchantId())
                .orElseThrow(this::notFound);
        verifySecret(challenge, clientSecret);
        if (challenge.getStatus() != AuthenticationChallengeStatus.PENDING) {
            return terminalResult(challenge, paymentIntent, paymentAttempt, context.merchantId());
        }
        Instant now = clock.instant();
        if (!now.isBefore(challenge.getExpiresAt())) {
            challenge.expire(now);
            paymentAttempt.markAuthenticationFailed(true);
            paymentIntent.markRequiresPaymentMethodAfterAuthenticationFailure();
            recordTerminalEvidence(context, requestId, challenge, paymentIntent, paymentAttempt, null);
            appendTerminalEvents(context, requestId, challenge, paymentIntent, paymentAttempt, null);
            return terminalResult(challenge, paymentIntent, paymentAttempt, context.merchantId());
        }
        if (outcome == CompletionOutcome.FAILURE) {
            challenge.fail(now);
            paymentAttempt.markAuthenticationFailed(false);
            paymentIntent.markRequiresPaymentMethodAfterAuthenticationFailure();
            recordTerminalEvidence(context, requestId, challenge, paymentIntent, paymentAttempt, null);
            appendTerminalEvents(context, requestId, challenge, paymentIntent, paymentAttempt, null);
            return terminalResult(challenge, paymentIntent, paymentAttempt, context.merchantId());
        }

        paymentAttempt.markReadyForProcessorAfterAuthentication();
        paymentIntent.markProcessingAfterAuthentication();
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndMerchantId(
                        paymentAttempt.getPaymentMethodId(), context.merchantId())
                .orElseThrow(this::notFound);
        ProcessorAuthorizationResult processorResult = processorAdapter.authorize(
                new ProcessorAuthorizationRequest(context.merchantId(), paymentIntent.getId(),
                        paymentAttempt.getId(), paymentMethod.getId(), paymentIntent.getAmount(),
                        paymentIntent.getCurrency(), paymentMethod.getCardBin(), paymentMethod.getCardLast4(), true));
        if (processorResult.status() == AuthorizationStatus.REQUIRES_ACTION) {
            authenticationMetrics.recordDenial("SECOND_CHALLENGE_LOOP");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Authenticated processor continuation requested another challenge");
        }
        Authorization authorization = persistOrCompleteAuthorization(
                context.merchantId(), challenge, paymentAttempt, processorResult);
        applyProcessorResult(paymentIntent, paymentAttempt, authorization);
        challenge.succeed(now);
        if (challenge.getAuthorizationId() == null) {
            challenge.attachAuthorization(authorization.getId());
        }
        recordTerminalEvidence(context, requestId, challenge, paymentIntent, paymentAttempt, authorization);
        DomainEventWriteResult terminalAttemptEvent = appendTerminalEvents(context, requestId,
                challenge, paymentIntent, paymentAttempt, authorization);
        if (authorization.getStatus() == AuthorizationStatus.APPROVED
                && paymentIntent.getCaptureMethod() == CaptureMethod.AUTOMATIC) {
            capture(context, requestId, paymentIntent, authorization,
                    paymentDomainEventService.merchantActor(context),
                    terminalAttemptEvent.eventId());
        }
        return new CompletionResult(challenge, authorization,
                paymentIntent.getStatus(), paymentAttempt.getStatus());
    }

    public DomainEventWriteResult cancelPending(MerchantContext context, UUID paymentIntentId,
            String requestId) {
        UUID merchantId = context.merchantId();
        AuthenticationChallenge challenge = challengeRepository
                .findByMerchantIdAndPaymentIntentIdAndStatus(
                        merchantId, paymentIntentId, AuthenticationChallengeStatus.PENDING)
                .orElse(null);
        if (challenge == null) {
            return null;
        }
        PaymentIntent paymentIntent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(
                        paymentIntentId, merchantId)
                .orElseThrow(this::notFound);
        PaymentAttempt attempt = paymentAttemptRepository.findByIdAndMerchantIdForUpdate(
                        challenge.getPaymentAttemptId(), merchantId)
                .orElseThrow(this::notFound);
        AuthenticationChallenge locked = challengeRepository.findByIdAndMerchantIdForUpdate(
                        challenge.getId(), merchantId)
                .orElseThrow(this::notFound);
        locked.cancel(clock.instant());
        attempt.cancelFromAuthentication();
        auditLogService.recordMerchantApiAction(context, requestId,
                "authentication_challenge.completed", "authentication_challenge",
                locked.getId(), "Simulator authentication challenge canceled with PaymentIntent", null,
                Map.of("source", locked.getSource().name(),
                        "status", locked.getStatus().name(),
                        "resultCode", locked.getResultCode().name(),
                        "paymentIntentStatus", PaymentIntentStatus.CANCELED.name(),
                        "paymentAttemptStatus", attempt.getStatus().name(),
                        "authorizationPresent", false));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(merchantId,
                "authentication_challenge.completed", locked.getId(), locked.getTrafficOrigin());
        authenticationMetrics.recordTerminal(locked.getSource(), locked.getResultCode());
        return appendTerminalEvents(context, requestId, locked, paymentIntent, attempt, null);
    }

    public AuthenticationNextAction nextActionForAttempt(UUID merchantId, UUID paymentAttemptId) {
        AuthenticationChallenge challenge = challengeRepository
                .findByMerchantIdAndPaymentAttemptId(merchantId, paymentAttemptId)
                .orElse(null);
        if (challenge == null) {
            return null;
        }
        return new AuthenticationNextAction(challenge.getId(), challenge.getSource(),
                challenge.getExpiresAt(), completionEndpoint(paymentAttemptId, challenge.getId()), null);
    }

    private Authorization persistOrCompleteAuthorization(UUID merchantId, AuthenticationChallenge challenge,
            PaymentAttempt attempt, ProcessorAuthorizationResult result) {
        if (challenge.getSource() == AuthenticationChallengeSource.PROCESSOR) {
            Authorization authorization = authorizationRepository.findByPaymentAttemptIdAndMerchantId(
                            attempt.getId(), merchantId)
                    .orElseThrow(this::notFound);
            if (result.status() == AuthorizationStatus.APPROVED) {
                authorization.approveAfterAuthentication(result.responseCode(), result.authorizedAmount(),
                        ThreeDsResult.AUTHENTICATED);
                authorizationRepository.flush();
            } else {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Authenticated processor continuation did not approve");
            }
            return authorization;
        }
        Authorization authorization = authorizationRepository.save(new Authorization(
                merchantId, attempt.getId(), result.processor(), result.processorReferenceId(),
                result.responseCode(), result.networkStatus(), result.requestedAmount(),
                result.authorizedAmount(), result.currency(), result.status(), result.cvcCheck(),
                result.avsLine1Check(), result.avsPostalCodeCheck(), result.threeDsResult(),
                result.simulatorOutcomeCode()));
        authorizationRepository.flush();
        return authorization;
    }

    private void applyProcessorResult(PaymentIntent intent, PaymentAttempt attempt, Authorization authorization) {
        if (authorization.getStatus() == AuthorizationStatus.APPROVED) {
            attempt.markAuthorized(authorization.getProcessorReferenceId());
            intent.markRequiresCaptureAfterAuthorization();
        } else if (authorization.getStatus() == AuthorizationStatus.DECLINED) {
            attempt.markDeclined(authorization.getProcessorReferenceId());
            intent.markRequiresPaymentMethodAfterProcessorDecline();
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Authenticated processor result is unsupported");
        }
    }

    private void capture(MerchantContext context, String requestId,
            PaymentIntent intent, Authorization authorization, DomainEventActor eventActor,
            UUID causationEventId) {
        LedgerService.CaptureLedgerResult ledger = ledgerService.recordCapture(
                context.merchantId(), intent.getId(), authorization.getAuthorizedAmount(),
                authorization.getCurrency());
        intent.capture();
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "payment_intent.captured", intent.getId(), intent.getTrafficOrigin());
        auditLogService.recordMerchantApiAction(context, requestId, "payment_intent.captured",
                "payment_intent", intent.getId(), "Authenticated PaymentIntent captured", null,
                Map.of("status", intent.getStatus().name(),
                        "authorizationId", authorization.getId().toString(),
                        "transactionGroupId", ledger.transactionGroupId().toString(),
                        "ledgerEntryIds", ledger.entries().stream()
                                .map(LedgerEntry::getId).map(UUID::toString).toList()));
        paymentDomainEventService.captureCreated(intent, authorization, intent.getUpdatedAt(),
                eventActor, requestId, causationEventId);
    }

    private DomainEventWriteResult appendTerminalEvents(MerchantContext context, String requestId,
            AuthenticationChallenge challenge, PaymentIntent paymentIntent, PaymentAttempt attempt,
            Authorization authorization) {
        DomainEventActor actor = paymentDomainEventService.merchantActor(context);
        DomainEventWriteResult challengeEvent = paymentDomainEventService.authenticationChallengeCompleted(
                paymentIntent, challenge, actor, requestId, null);
        DomainEventWriteResult authorizationEvent = null;
        if (authorization != null && authorization.getStatus() == AuthorizationStatus.APPROVED) {
            authorizationEvent = paymentDomainEventService.authorizationCreated(paymentIntent,
                    attempt, authorization, challenge.getCompletedAt(), actor, requestId,
                    challengeEvent.eventId());
        }
        UUID attemptCause = authorizationEvent == null
                ? challengeEvent.eventId() : authorizationEvent.eventId();
        DomainEventWriteResult attemptEvent = paymentDomainEventService.paymentAttemptCompleted(
                paymentIntent, attempt, authorization,
                actor, requestId, attemptCause);
        return attemptEvent;
    }

    private void recordTerminalEvidence(MerchantContext context, String requestId,
            AuthenticationChallenge challenge, PaymentIntent intent, PaymentAttempt attempt,
            Authorization authorization) {
        auditLogService.recordMerchantApiAction(context, requestId,
                "authentication_challenge.completed", "authentication_challenge",
                challenge.getId(), "Simulator authentication challenge completed", null,
                Map.of("source", challenge.getSource().name(),
                        "status", challenge.getStatus().name(),
                        "resultCode", challenge.getResultCode().name(),
                        "paymentIntentStatus", intent.getStatus().name(),
                        "paymentAttemptStatus", attempt.getStatus().name(),
                        "authorizationPresent", authorization != null));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "authentication_challenge.completed", challenge.getId(), challenge.getTrafficOrigin());
        authenticationMetrics.recordTerminal(challenge.getSource(), challenge.getResultCode());
    }

    private CompletionResult terminalResult(AuthenticationChallenge challenge,
            PaymentIntent intent, PaymentAttempt attempt, UUID merchantId) {
        Authorization authorization = authorizationRepository
                .findByPaymentAttemptIdAndMerchantId(attempt.getId(), merchantId).orElse(null);
        return new CompletionResult(challenge, authorization, intent.getStatus(), attempt.getStatus());
    }

    private void verifySecret(AuthenticationChallenge challenge, String secret) {
        if (secret == null || !secret.matches("authc_[A-Za-z0-9_-]{43}")) {
            authenticationMetrics.recordDenial("INVALID_SECRET_GRAMMAR");
            throw notFound();
        }
        String hmac = sensitiveValueHasher.hmacSha256(
                SECRET_DOMAIN, secret, challenge.getClientSecretKeyVersion());
        if (!constantTimeEquals(hmac, challenge.getClientSecretIdentityHmac())
                || !constantTimeEquals(sha256(secret), challenge.getClientSecretSha256())) {
            authenticationMetrics.recordDenial("SECRET_MISMATCH");
            throw notFound();
        }
    }

    private String newSecret() {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        return "authc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private String completionEndpoint(UUID attemptId, UUID challengeId) {
        return "/payment_attempts/" + attemptId + "/authentication_challenges/"
                + challengeId + "/complete";
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Authentication challenge was not found");
    }

    public enum CompletionOutcome { SUCCESS, FAILURE }

    public record CreatedChallenge(AuthenticationChallenge challenge,
            AuthenticationNextAction nextAction) {
    }

    public record CompletionResult(AuthenticationChallenge challenge, Authorization authorization,
            PaymentIntentStatus paymentIntentStatus, PaymentAttemptStatus paymentAttemptStatus) {
    }
}
