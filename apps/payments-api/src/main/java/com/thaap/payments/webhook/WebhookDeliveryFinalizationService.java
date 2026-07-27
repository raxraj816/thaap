package com.thaap.payments.webhook;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebhookDeliveryFinalizationService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookRetryPolicy retryPolicy;
    private final TransactionTemplate finalizationTransaction;

    public WebhookDeliveryFinalizationService(WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookEndpointRepository endpointRepository, WebhookRetryPolicy retryPolicy,
            PlatformTransactionManager transactionManager) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.endpointRepository = endpointRepository;
        this.retryPolicy = retryPolicy;
        this.finalizationTransaction = new TransactionTemplate(transactionManager);
        this.finalizationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public WebhookDeliveryFinalizationResult succeed(
            WebhookDeliveryClaim claim, Instant completedAt, int responseStatus) {
        return finalizationTransaction.execute(status -> {
            ClaimState state = loadCurrentClaim(claim);
            if (state == null) {
                return WebhookDeliveryFinalizationResult.stale();
            }
            state.attempt().succeed(completedAt, responseStatus);
            state.delivery().succeed(claim.claimToken(), completedAt);
            return WebhookDeliveryFinalizationResult.finalized(state.delivery(), state.attempt());
        });
    }

    public WebhookDeliveryFinalizationResult failRetryably(
            WebhookDeliveryClaim claim, Instant completedAt, Boolean requestSent,
            Integer responseStatus, WebhookDeliveryErrorCode errorCode) {
        return finalizationTransaction.execute(status -> {
            WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantIdForShare(
                            claim.endpointId(), claim.merchantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook endpoint disappeared during finalization"));
            ClaimState state = loadCurrentClaim(claim);
            if (state == null) {
                return WebhookDeliveryFinalizationResult.stale();
            }
            if (requestSent == null) {
                state.attempt().markOutcomeUnknown(completedAt, errorCode);
                if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
                    state.delivery().exhaust(claim.claimToken(), WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
                } else if (retryPolicy.isExhausted(claim.generationAttemptNumber())) {
                    state.delivery().exhaust(claim.claimToken(), errorCode);
                } else {
                    state.delivery().scheduleRetry(claim.claimToken(),
                            completedAt.plus(retryPolicy.delayBeforeNextAttempt(
                                    claim.generationAttemptNumber())), errorCode);
                }
            } else if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
                state.attempt().failTerminally(completedAt, requestSent, responseStatus,
                        WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
                state.delivery().exhaust(claim.claimToken(), WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
            } else if (retryPolicy.isExhausted(claim.generationAttemptNumber())) {
                state.attempt().failTerminally(completedAt, requestSent, responseStatus, errorCode);
                state.delivery().exhaust(claim.claimToken(), errorCode);
            } else {
                state.attempt().failRetryably(completedAt, requestSent, responseStatus, errorCode);
                state.delivery().scheduleRetry(claim.claimToken(),
                        completedAt.plus(retryPolicy.delayBeforeNextAttempt(
                                claim.generationAttemptNumber())), errorCode);
            }
            return WebhookDeliveryFinalizationResult.finalized(state.delivery(), state.attempt());
        });
    }

    public WebhookDeliveryFinalizationResult failTerminally(
            WebhookDeliveryClaim claim, Instant completedAt,
            WebhookDeliveryErrorCode errorCode) {
        return failTerminally(claim, completedAt, false, null, errorCode);
    }

    public WebhookDeliveryFinalizationResult failTerminally(
            WebhookDeliveryClaim claim, Instant completedAt, boolean requestSent,
            Integer responseStatus, WebhookDeliveryErrorCode errorCode) {
        return finalizationTransaction.execute(status -> {
            ClaimState state = loadCurrentClaim(claim);
            if (state == null) {
                return WebhookDeliveryFinalizationResult.stale();
            }
            state.attempt().failTerminally(completedAt, requestSent, responseStatus, errorCode);
            state.delivery().exhaust(claim.claimToken(), errorCode);
            return WebhookDeliveryFinalizationResult.finalized(state.delivery(), state.attempt());
        });
    }

    private ClaimState loadCurrentClaim(WebhookDeliveryClaim claim) {
        WebhookDelivery delivery = deliveryRepository.findByIdForUpdate(claim.deliveryId())
                .orElseThrow(() -> new IllegalStateException("Webhook delivery disappeared during finalization"));
        if (!delivery.matchesClaim(claim.claimToken())
                || delivery.getAttemptCount() != claim.attemptNumber()) {
            return null;
        }
        WebhookDeliveryAttempt attempt = attemptRepository.findByWebhookDeliveryIdAndAttemptNumberForUpdate(
                        claim.deliveryId(), claim.attemptNumber())
                .orElseThrow(() -> new IllegalStateException("Webhook attempt disappeared during finalization"));
        if (attempt.getOutcome() != WebhookAttemptOutcome.IN_PROGRESS) {
            return null;
        }
        return new ClaimState(delivery, attempt);
    }

    private record ClaimState(WebhookDelivery delivery, WebhookDeliveryAttempt attempt) {
    }
}
