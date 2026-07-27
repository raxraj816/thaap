package com.thaap.payments.webhook;

import com.thaap.payments.security.SecretHasher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final int maxClaimsPerMerchantPerPoll;
    private final int maxClaimsPerEndpointPerPoll;

    @Autowired
    public WebhookDeliveryExecutor(WebhookDeliveryClaimService claimService,
            WebhookDeliveryFinalizationService finalizationService,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointSecretService endpointSecretService, WebhookSigner signer,
            WebhookTargetPolicy targetPolicy, WebhookHttpTransport httpTransport,
            WebhookMetrics metrics, Clock clock,
            @Value("${thaap.webhook.max-claims-per-merchant-per-poll:5}")
            int maxClaimsPerMerchantPerPoll,
            @Value("${thaap.webhook.max-claims-per-endpoint-per-poll:2}")
            int maxClaimsPerEndpointPerPoll) {
        if (maxClaimsPerMerchantPerPoll < 1 || maxClaimsPerMerchantPerPoll > 100) {
            throw new IllegalArgumentException(
                    "Webhook per-merchant claim limit must be between 1 and 100");
        }
        if (maxClaimsPerEndpointPerPoll < 1
                || maxClaimsPerEndpointPerPoll > maxClaimsPerMerchantPerPoll) {
            throw new IllegalArgumentException(
                    "Webhook per-endpoint claim limit must be positive and no greater "
                            + "than the per-merchant limit");
        }
        this.claimService = claimService;
        this.finalizationService = finalizationService;
        this.deliveryRepository = deliveryRepository;
        this.endpointSecretService = endpointSecretService;
        this.signer = signer;
        this.targetPolicy = targetPolicy;
        this.httpTransport = httpTransport;
        this.metrics = metrics;
        this.clock = clock;
        this.maxClaimsPerMerchantPerPoll = maxClaimsPerMerchantPerPoll;
        this.maxClaimsPerEndpointPerPoll = maxClaimsPerEndpointPerPoll;
    }

    WebhookDeliveryExecutor(WebhookDeliveryClaimService claimService,
            WebhookDeliveryFinalizationService finalizationService,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointSecretService endpointSecretService, WebhookSigner signer,
            WebhookTargetPolicy targetPolicy, WebhookHttpTransport httpTransport,
            WebhookMetrics metrics, Clock clock) {
        this(claimService, finalizationService, deliveryRepository, endpointSecretService,
                signer, targetPolicy, httpTransport, metrics, clock, 5, 2);
    }

    public int processDueDeliveries(Instant now, int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Webhook processing batch size must be between 1 and 100");
        }
        int processed = 0;
        Map<UUID, Integer> merchantClaims = new HashMap<>();
        Map<UUID, Integer> endpointClaims = new HashMap<>();
        Set<UUID> saturatedMerchants = new HashSet<>();
        Set<UUID> saturatedEndpoints = new HashSet<>();
        for (int index = 0; index < batchSize; index++) {
            Instant claimAt = laterOf(now, clock.instant());
            List<WebhookDeliveryClaim> claims = claimService.claimDue(
                    claimAt, 1, saturatedMerchants, saturatedEndpoints);
            if (claims.isEmpty()) {
                break;
            }
            WebhookDeliveryClaim claim = claims.getFirst();
            int merchantCount = merchantClaims.merge(claim.merchantId(), 1, Integer::sum);
            if (merchantCount == maxClaimsPerMerchantPerPoll
                    && saturatedMerchants.add(claim.merchantId())) {
                metrics.recordClaimThrottled("merchant");
            }
            int endpointCount = endpointClaims.merge(claim.endpointId(), 1, Integer::sum);
            if (endpointCount == maxClaimsPerEndpointPerPoll
                    && saturatedEndpoints.add(claim.endpointId())) {
                metrics.recordClaimThrottled("endpoint");
            }
            processClaim(claim);
            processed++;
        }
        refreshDueDeliveries(laterOf(now, clock.instant()));
        return processed;
    }

    public void refreshDueDeliveries(Instant now) {
        metrics.setDueDeliveries(deliveryRepository.countDue(now));
    }

    void processClaim(WebhookDeliveryClaim claim) {
        Instant attemptStartedAt = claim.claimedAt();
        metrics.recordQueueAge(claim.eventType(), Duration.between(claim.deliveryCreatedAt(), attemptStartedAt));
        try {
            requirePayloadIntegrity(claim);
            ValidatedWebhookTarget target = targetPolicy.validate(claim.endpointUrl());
            Instant preparationStartedAt = laterOf(attemptStartedAt, clock.instant());
            if (!claimService.prepareForDispatch(claim, preparationStartedAt)) {
                LOGGER.warn("Skipped stale webhook claim before HTTP deliveryId={} eventId={} attempt={}",
                        claim.deliveryId(), claim.eventId(), claim.attemptNumber());
                return;
            }
            WebhookSignature signature;
            try {
                signature = signForDelivery(claim, preparationStartedAt);
            } catch (WebhookSecretException exception) {
                throw exception;
            } catch (WebhookSigningException exception) {
                LOGGER.error("Webhook signing failed after dispatch preparation; recording known no-send "
                                + "deliveryId={} eventId={} attempt={}",
                        claim.deliveryId(), claim.eventId(), claim.attemptNumber(), exception);
                retryableNoRequestFailure(
                        claim, attemptStartedAt, WebhookDeliveryErrorCode.SIGNING_FAILED);
                return;
            }
            WebhookHttpResponse response = httpTransport.send(new WebhookHttpRequest(
                    target, claim.eventId(), claim.deliveryId(), signature.headerValue(), claim.payload()));
            Instant completedAt = completedAt(attemptStartedAt);
            if (response.isSuccessful()) {
                WebhookDeliveryFinalizationResult result =
                        finalizationService.succeed(claim, completedAt, response.statusCode());
                recordFinalization(claim, result, attemptStartedAt, completedAt);
            } else if (isRetryableHttpStatus(response.statusCode())) {
                WebhookDeliveryFinalizationResult result = finalizationService.failRetryably(
                        claim, completedAt, true, response.statusCode(),
                        WebhookDeliveryErrorCode.HTTP_NON_SUCCESS);
                recordFinalization(claim, result, attemptStartedAt, completedAt);
            } else {
                WebhookDeliveryFinalizationResult result = finalizationService.failTerminally(
                        claim, completedAt, true, response.statusCode(),
                        WebhookDeliveryErrorCode.HTTP_NON_SUCCESS);
                recordFinalization(claim, result, attemptStartedAt, completedAt);
            }
        } catch (WebhookTargetResolutionException exception) {
            retryableNoRequestFailure(claim, attemptStartedAt, exception.getErrorCode());
        } catch (WebhookTargetPolicyException exception) {
            terminalFailure(claim, attemptStartedAt, WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED);
        } catch (WebhookStaleSecretGenerationException exception) {
            retryableNoRequestFailure(claim, attemptStartedAt, exception.getErrorCode());
        } catch (WebhookSecretException exception) {
            terminalFailure(claim, attemptStartedAt, exception.getErrorCode());
        } catch (WebhookTransportException exception) {
            if (exception.getErrorCode() == WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED
                    && !Boolean.TRUE.equals(exception.getRequestSent())) {
                terminalFailure(claim, attemptStartedAt, exception.getErrorCode());
                return;
            }
            Instant completedAt = completedAt(attemptStartedAt);
            WebhookDeliveryFinalizationResult result = finalizationService.failRetryably(
                    claim, completedAt, exception.getRequestSent(), null, exception.getErrorCode());
            recordFinalization(claim, result, attemptStartedAt, completedAt);
        } catch (RuntimeException exception) {
            LOGGER.error("Webhook delivery execution failed unexpectedly; lease recovery will retry "
                            + "deliveryId={} eventId={} attempt={}",
                    claim.deliveryId(), claim.eventId(), claim.attemptNumber(), exception);
        }
    }

    private WebhookSignature signForDelivery(
            WebhookDeliveryClaim claim, Instant preparationStartedAt) {
        try {
            return endpointSecretService.signForDelivery(
                    claim.merchantId(), claim.endpointId(),
                    claim.signingSecretGeneration(), claim.payload(),
                    preparationStartedAt, signer);
        } catch (WebhookStaleSecretGenerationException exception) {
            if (claim.generationAttemptNumber() < WebhookRetryPolicy.MAX_ATTEMPTS) {
                throw exception;
            }
            LOGGER.warn("Final webhook attempt is rebinding a stale secret generation before HTTP "
                            + "deliveryId={} eventId={} attempt={}",
                    claim.deliveryId(), claim.eventId(), claim.attemptNumber());
            return endpointSecretService.signForDelivery(
                    claim.merchantId(), claim.endpointId(), null, claim.payload(),
                    preparationStartedAt, signer);
        }
    }

    private void retryableNoRequestFailure(WebhookDeliveryClaim claim, Instant startedAt,
            WebhookDeliveryErrorCode errorCode) {
        Instant completedAt = completedAt(startedAt);
        WebhookDeliveryFinalizationResult result =
                finalizationService.failRetryably(claim, completedAt, false, null, errorCode);
        recordFinalization(claim, result, startedAt, completedAt);
    }

    static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || (statusCode >= 500 && statusCode <= 599);
    }

    private void requirePayloadIntegrity(WebhookDeliveryClaim claim) {
        int payloadBytes = claim.payload().getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > WebhookEventEnvelopeFactory.MAX_PAYLOAD_BYTES) {
            throw new WebhookSecretException("Webhook event payload exceeded the delivery byte limit",
                    WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED);
        }
        if (!SecretHasher.sha256(claim.payload()).equals(claim.payloadSha256())) {
            throw new WebhookSecretException("Webhook event payload integrity check failed",
                    WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED);
        }
    }

    private void terminalFailure(WebhookDeliveryClaim claim, Instant startedAt,
            WebhookDeliveryErrorCode errorCode) {
        Instant completedAt = completedAt(startedAt);
        WebhookDeliveryFinalizationResult result =
                finalizationService.failTerminally(claim, completedAt, errorCode);
        recordFinalization(claim, result, startedAt, completedAt);
    }

    private void recordFinalization(WebhookDeliveryClaim claim,
            WebhookDeliveryFinalizationResult result,
            Instant startedAt, Instant completedAt) {
        if (!result.finalized()) {
            LOGGER.warn("Ignored stale webhook finalization deliveryId={} eventId={} attempt={}",
                    claim.deliveryId(), claim.eventId(), claim.attemptNumber());
            return;
        }
        Duration attemptDuration = Duration.between(startedAt, completedAt);
        metrics.recordAttempt(claim.eventType(), result.attemptOutcome().name(), attemptDuration);
        WebhookDeliveryErrorCode effectiveErrorCode = result.deliveryErrorCode() == null
                ? result.attemptErrorCode()
                : result.deliveryErrorCode();
        if (effectiveErrorCode != null) {
            metrics.recordFailure(claim.eventType(), effectiveErrorCode);
        }
        if (result.deliveryStatus() == WebhookDeliveryStatus.SUCCEEDED
                || result.deliveryStatus() == WebhookDeliveryStatus.EXHAUSTED) {
            metrics.recordTerminal(claim.eventType(), result.deliveryStatus(),
                    Duration.between(claim.deliveryCreatedAt(), completedAt));
        }
        LOGGER.info("Webhook delivery attempt finalized deliveryId={} eventId={} attempt={} "
                        + "outcome={} errorCode={} latencyMs={}",
                claim.deliveryId(), claim.eventId(), claim.attemptNumber(), result.attemptOutcome(),
                effectiveErrorCode, attemptDuration.toMillis());
    }

    private Instant completedAt(Instant startedAt) {
        Instant observed = clock.instant();
        return observed.isBefore(startedAt) ? startedAt : observed;
    }

    private Instant laterOf(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }
}
