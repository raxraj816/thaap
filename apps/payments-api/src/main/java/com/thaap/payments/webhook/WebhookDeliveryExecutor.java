package com.thaap.payments.webhook;

import com.thaap.payments.security.SecretHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookDeliveryExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryExecutor.class);

    private final WebhookDeliveryClaimService claimService;
    private final WebhookDeliveryFinalizationService finalizationService;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointSecretService endpointSecretService;
    private final WebhookSigner signer;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookHttpTransport httpTransport;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public WebhookDeliveryExecutor(WebhookDeliveryClaimService claimService,
            WebhookDeliveryFinalizationService finalizationService,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointSecretService endpointSecretService, WebhookSigner signer,
            WebhookTargetPolicy targetPolicy, WebhookHttpTransport httpTransport,
            WebhookMetrics metrics, Clock clock) {
        this.claimService = claimService;
        this.finalizationService = finalizationService;
        this.deliveryRepository = deliveryRepository;
        this.endpointSecretService = endpointSecretService;
        this.signer = signer;
        this.targetPolicy = targetPolicy;
        this.httpTransport = httpTransport;
        this.metrics = metrics;
        this.clock = clock;
    }

    public int processDueDeliveries(Instant now, int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Webhook processing batch size must be between 1 and 100");
        }
        int processed = 0;
        for (int index = 0; index < batchSize; index++) {
            Instant claimAt = laterOf(now, clock.instant());
            List<WebhookDeliveryClaim> claims = claimService.claimDue(claimAt, 1);
            if (claims.isEmpty()) {
                break;
            }
            processClaim(claims.getFirst());
            processed++;
        }
        metrics.setDueDeliveries(deliveryRepository.countDue(laterOf(now, clock.instant())));
        return processed;
    }

    void processClaim(WebhookDeliveryClaim claim) {
        Instant attemptStartedAt = claim.claimedAt();
        metrics.recordQueueAge(claim.eventType(), Duration.between(claim.deliveryCreatedAt(), attemptStartedAt));
        try {
            requirePayloadIntegrity(claim);
            String rawSecret = endpointSecretService.loadForDelivery(claim.merchantId(), claim.endpointId());
            ValidatedWebhookTarget target = targetPolicy.validate(claim.endpointUrl());
            Instant sendStartedAt = laterOf(attemptStartedAt, clock.instant());
            WebhookSignature signature = signer.sign(rawSecret, claim.payload(), sendStartedAt);
            if (!claimService.prepareForDispatch(claim, sendStartedAt)) {
                LOGGER.warn("Skipped stale webhook claim before HTTP deliveryId={} eventId={} attempt={}",
                        claim.deliveryId(), claim.eventId(), claim.attemptNumber());
                return;
            }
            WebhookHttpResponse response = httpTransport.send(new WebhookHttpRequest(
                    target, claim.eventId(), claim.deliveryId(), signature.headerValue(), claim.payload()));
            Instant completedAt = completedAt(attemptStartedAt);
            if (response.isSuccessful()) {
                boolean finalized = finalizationService.succeed(claim, completedAt, response.statusCode());
                recordFinalization(claim, finalized, WebhookAttemptOutcome.SUCCEEDED, null,
                        WebhookDeliveryStatus.SUCCEEDED, attemptStartedAt, completedAt);
            } else {
                boolean finalized = finalizationService.failRetryably(claim, completedAt, true,
                        response.statusCode(), WebhookDeliveryErrorCode.HTTP_NON_SUCCESS);
                WebhookDeliveryStatus terminal = claim.attemptNumber() >= WebhookRetryPolicy.MAX_ATTEMPTS
                        ? WebhookDeliveryStatus.EXHAUSTED
                        : null;
                recordFinalization(claim, finalized, WebhookAttemptOutcome.RETRYABLE_FAILURE,
                        WebhookDeliveryErrorCode.HTTP_NON_SUCCESS, terminal, attemptStartedAt, completedAt);
            }
        } catch (WebhookTargetResolutionException exception) {
            retryableNoRequestFailure(claim, attemptStartedAt, exception.getErrorCode());
        } catch (WebhookTargetPolicyException exception) {
            terminalFailure(claim, attemptStartedAt, WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED);
        } catch (WebhookSecretException exception) {
            terminalFailure(claim, attemptStartedAt, exception.getErrorCode());
        } catch (WebhookTransportException exception) {
            Instant completedAt = completedAt(attemptStartedAt);
            boolean finalized = finalizationService.failRetryably(claim, completedAt, exception.getRequestSent(),
                    null, exception.getErrorCode());
            WebhookDeliveryStatus terminal = claim.attemptNumber() >= WebhookRetryPolicy.MAX_ATTEMPTS
                    ? WebhookDeliveryStatus.EXHAUSTED
                    : null;
            WebhookAttemptOutcome outcome = exception.getRequestSent() == null
                    ? WebhookAttemptOutcome.OUTCOME_UNKNOWN
                    : WebhookAttemptOutcome.RETRYABLE_FAILURE;
            recordFinalization(claim, finalized, outcome,
                    exception.getErrorCode(), terminal, attemptStartedAt, completedAt);
        } catch (RuntimeException exception) {
            LOGGER.error("Webhook delivery execution failed unexpectedly; lease recovery will retry "
                            + "deliveryId={} eventId={} attempt={}",
                    claim.deliveryId(), claim.eventId(), claim.attemptNumber(), exception);
        }
    }

    private void retryableNoRequestFailure(WebhookDeliveryClaim claim, Instant startedAt,
            WebhookDeliveryErrorCode errorCode) {
        Instant completedAt = completedAt(startedAt);
        boolean finalized = finalizationService.failRetryably(claim, completedAt, false, null, errorCode);
        WebhookDeliveryStatus terminal = claim.attemptNumber() >= WebhookRetryPolicy.MAX_ATTEMPTS
                ? WebhookDeliveryStatus.EXHAUSTED
                : null;
        recordFinalization(claim, finalized, WebhookAttemptOutcome.RETRYABLE_FAILURE,
                errorCode, terminal, startedAt, completedAt);
    }

    private void requirePayloadIntegrity(WebhookDeliveryClaim claim) {
        if (!SecretHasher.sha256(claim.payload()).equals(claim.payloadSha256())) {
            throw new WebhookSecretException("Webhook event payload integrity check failed",
                    WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED);
        }
    }

    private void terminalFailure(WebhookDeliveryClaim claim, Instant startedAt,
            WebhookDeliveryErrorCode errorCode) {
        Instant completedAt = completedAt(startedAt);
        boolean finalized = finalizationService.failTerminally(claim, completedAt, errorCode);
        recordFinalization(claim, finalized, WebhookAttemptOutcome.TERMINAL_FAILURE,
                errorCode, WebhookDeliveryStatus.EXHAUSTED, startedAt, completedAt);
    }

    private void recordFinalization(WebhookDeliveryClaim claim, boolean finalized,
            WebhookAttemptOutcome attemptOutcome, WebhookDeliveryErrorCode errorCode,
            WebhookDeliveryStatus terminalStatus, Instant startedAt, Instant completedAt) {
        if (!finalized) {
            LOGGER.warn("Ignored stale webhook finalization deliveryId={} eventId={} attempt={}",
                    claim.deliveryId(), claim.eventId(), claim.attemptNumber());
            return;
        }
        Duration attemptDuration = Duration.between(startedAt, completedAt);
        WebhookDeliveryStatus finalizedStatus = terminalStatus;
        if (terminalStatus == null) {
            finalizedStatus = claimFinalizedStatus(claim);
            if (finalizedStatus == WebhookDeliveryStatus.SUCCEEDED
                    || finalizedStatus == WebhookDeliveryStatus.EXHAUSTED) {
                terminalStatus = finalizedStatus;
            }
        }
        if (finalizedStatus == WebhookDeliveryStatus.EXHAUSTED
                && attemptOutcome == WebhookAttemptOutcome.RETRYABLE_FAILURE) {
            attemptOutcome = WebhookAttemptOutcome.TERMINAL_FAILURE;
        }
        metrics.recordAttempt(claim.eventType(), attemptOutcome.name(), attemptDuration);
        if (terminalStatus != null) {
            metrics.recordTerminal(claim.eventType(), terminalStatus,
                    Duration.between(claim.deliveryCreatedAt(), completedAt));
        }
        LOGGER.info("Webhook delivery attempt finalized deliveryId={} eventId={} attempt={} "
                        + "outcome={} errorCode={} latencyMs={}",
                claim.deliveryId(), claim.eventId(), claim.attemptNumber(), attemptOutcome,
                errorCode, attemptDuration.toMillis());
    }

    private WebhookDeliveryStatus claimFinalizedStatus(WebhookDeliveryClaim claim) {
        return deliveryRepository.findById(claim.deliveryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Webhook delivery disappeared after finalization"))
                .getStatus();
    }

    private Instant completedAt(Instant startedAt) {
        Instant observed = clock.instant();
        return observed.isBefore(startedAt) ? startedAt : observed;
    }

    private Instant laterOf(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }
}
