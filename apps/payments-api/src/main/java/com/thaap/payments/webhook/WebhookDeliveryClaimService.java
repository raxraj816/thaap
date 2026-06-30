package com.thaap.payments.webhook;

import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebhookDeliveryClaimService {

    private final JdbcTemplate jdbcTemplate;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookEventRepository eventRepository;
    private final WebhookMetrics metrics;
    private final EntityManager entityManager;
    private final TransactionTemplate claimTransaction;
    private final Duration leaseDuration;

    public WebhookDeliveryClaimService(JdbcTemplate jdbcTemplate,
            WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookEndpointRepository endpointRepository,
            WebhookEventRepository eventRepository, WebhookMetrics metrics,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            @Value("${thaap.webhook.lease-duration:30s}") Duration leaseDuration) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Webhook lease duration must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.metrics = metrics;
        this.entityManager = entityManager;
        this.leaseDuration = leaseDuration;
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.claimTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<WebhookDeliveryClaim> claimDue(Instant now, int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Webhook claim batch size must be between 1 and 100");
        }
        ClaimBatch batch = claimTransaction.execute(
                status -> claimDueInTransaction(now, batchSize));
        if (batch == null) {
            return List.of();
        }
        for (RecoveryObservation recovery : batch.recoveries()) {
            metrics.recordAttempt(recovery.eventType(),
                    recovery.outcome().name(), recovery.attemptDuration());
            if (recovery.exhausted()) {
                metrics.recordTerminal(recovery.eventType(), WebhookDeliveryStatus.EXHAUSTED,
                        recovery.deliveryLatency());
            }
        }
        return batch.claims();
    }

    public boolean prepareForDispatch(WebhookDeliveryClaim claim, Instant now) {
        DispatchPreparation preparation = claimTransaction.execute(status -> {
            WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantIdForShare(
                            claim.endpointId(), claim.merchantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook endpoint disappeared during dispatch preparation"));
            WebhookDelivery delivery = deliveryRepository.findByIdForUpdate(claim.deliveryId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook delivery disappeared during lease renewal"));
            if (!delivery.matchesClaim(claim.claimToken())
                    || delivery.getAttemptCount() != claim.attemptNumber()) {
                return DispatchPreparation.skipped();
            }
            WebhookDeliveryAttempt attempt = attemptRepository.findByWebhookDeliveryIdAndAttemptNumberForUpdate(
                            claim.deliveryId(), claim.attemptNumber())
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook attempt disappeared during dispatch preparation"));
            if (attempt.getOutcome() != WebhookAttemptOutcome.IN_PROGRESS) {
                return DispatchPreparation.skipped();
            }
            if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
                attempt.failTerminally(now, false, null, WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
                delivery.exhaust(claim.claimToken(), WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
                return DispatchPreparation.disabled(claim.eventType(),
                        Duration.between(attempt.getStartedAt(), now),
                        Duration.between(delivery.getCreatedAt(), now));
            }
            if (endpoint.getOperationalStatus() != WebhookEndpointStatus.ACTIVE) {
                attempt.failTerminally(now, false, null,
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
                delivery.exhaust(claim.claimToken(),
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
                return DispatchPreparation.unavailable(claim.eventType(),
                        Duration.between(attempt.getStartedAt(), now),
                        Duration.between(delivery.getCreatedAt(), now));
            }
            attempt.markDispatchStarted(now);
            delivery.renewClaim(claim.claimToken(), now.plus(leaseDuration));
            return DispatchPreparation.ready();
        });
        if (preparation == null) {
            return false;
        }
        if (preparation.terminal()) {
            metrics.recordAttempt(preparation.eventType(), WebhookAttemptOutcome.TERMINAL_FAILURE.name(),
                    preparation.attemptDuration());
            metrics.recordTerminal(preparation.eventType(), WebhookDeliveryStatus.EXHAUSTED,
                    preparation.deliveryLatency());
        }
        return preparation.prepared();
    }

    private ClaimBatch claimDueInTransaction(Instant now, int batchSize) {
        List<UUID> deliveryIds = jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT delivery.id
                    FROM webhook_deliveries delivery
                    WHERE (
                        delivery.status = 'DELIVERING'
                        AND delivery.lease_expires_at <= ?
                    )
                    OR (
                        EXISTS (
                            SELECT 1
                            FROM webhook_endpoints endpoint
                            WHERE endpoint.merchant_id = delivery.merchant_id
                              AND endpoint.id = delivery.webhook_endpoint_id
                              AND endpoint.operational_status = 'ACTIVE'
                        )
                        AND (
                            delivery.status = 'PENDING'
                            OR (
                                delivery.status = 'RETRY_SCHEDULED'
                                AND delivery.next_attempt_at <= ?
                            )
                          )
                        )
                    ORDER BY
                        COALESCE(delivery.next_attempt_at, delivery.created_at),
                        delivery.created_at,
                        delivery.id
                    FOR UPDATE SKIP LOCKED
                    """);
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setMaxRows(batchSize);
            return statement;
        }, (resultSet, rowNumber) -> {
            Object value = resultSet.getObject(1);
            return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
        });

        List<WebhookDeliveryClaim> claims = new ArrayList<>();
        List<RecoveryObservation> recoveries = new ArrayList<>();
        for (UUID deliveryId : deliveryIds) {
            WebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Claimed webhook delivery disappeared"));
            WebhookEndpoint endpoint = endpointRepository.findById(delivery.getWebhookEndpointId())
                    .orElseThrow(() -> new IllegalStateException("Webhook endpoint lineage disappeared"));
            if (delivery.getStatus() == WebhookDeliveryStatus.DELIVERING) {
                WebhookDeliveryAttempt expiredAttempt = closeExpiredAttempt(delivery, now);
                boolean attemptsExhausted =
                        delivery.getAttemptCount() >= WebhookRetryPolicy.MAX_ATTEMPTS;
                boolean endpointDisabled =
                        endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED;
                recoveries.add(new RecoveryObservation(delivery.getEventType(),
                        expiredAttempt.getOutcome(),
                        Duration.between(expiredAttempt.getStartedAt(), now),
                        Duration.between(delivery.getCreatedAt(), now),
                        attemptsExhausted || endpointDisabled));
                if (endpointDisabled) {
                    delivery.exhaust(delivery.getClaimToken(),
                            WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
                    continue;
                }
                if (attemptsExhausted) {
                    delivery.exhaust(delivery.getClaimToken(), WebhookDeliveryErrorCode.LEASE_EXPIRED);
                    continue;
                }
                if (endpoint.getOperationalStatus() != WebhookEndpointStatus.ACTIVE) {
                    delivery.scheduleRetry(delivery.getClaimToken(), now,
                            WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
                    continue;
                }
            }
            WebhookEvent event = eventRepository.findById(delivery.getWebhookEventId())
                    .orElseThrow(() -> new IllegalStateException("Webhook event lineage disappeared"));
            UUID claimToken = delivery.claim(now, now.plus(leaseDuration));
            WebhookDeliveryAttempt attempt = attemptRepository.save(new WebhookDeliveryAttempt(
                    delivery.getMerchantId(), delivery.getId(), delivery.getAttemptCount(), now));
            claims.add(new WebhookDeliveryClaim(delivery.getId(), delivery.getMerchantId(),
                    endpoint.getId(), event.getId(), event.getEventType(), endpoint.getUrl(),
                    event.getPayload(), event.getPayloadSha256(), claimToken,
                    attempt.getAttemptNumber(), now, delivery.getCreatedAt()));
        }
        entityManager.flush();
        return new ClaimBatch(List.copyOf(claims), List.copyOf(recoveries));
    }

    private WebhookDeliveryAttempt closeExpiredAttempt(WebhookDelivery delivery, Instant now) {
        WebhookDeliveryAttempt attempt = attemptRepository.findByWebhookDeliveryIdAndAttemptNumberForUpdate(
                        delivery.getId(), delivery.getAttemptCount())
                .orElseThrow(() -> new IllegalStateException(
                        "Expired webhook delivery lease has no attempt evidence"));
        if (attempt.getDispatchStartedAt() == null) {
            if (delivery.getAttemptCount() >= WebhookRetryPolicy.MAX_ATTEMPTS) {
                attempt.failTerminally(now, false, null, WebhookDeliveryErrorCode.LEASE_EXPIRED);
            } else {
                attempt.failRetryably(now, false, null, WebhookDeliveryErrorCode.LEASE_EXPIRED);
            }
        } else {
            attempt.markOutcomeUnknown(now, WebhookDeliveryErrorCode.LEASE_EXPIRED);
        }
        return attempt;
    }

    private record ClaimBatch(
            List<WebhookDeliveryClaim> claims,
            List<RecoveryObservation> recoveries) {
    }

    private record RecoveryObservation(
            String eventType,
            WebhookAttemptOutcome outcome,
            Duration attemptDuration,
            Duration deliveryLatency,
            boolean exhausted) {
    }

    private record DispatchPreparation(
            boolean prepared,
            boolean terminal,
            String eventType,
            Duration attemptDuration,
            Duration deliveryLatency) {

        private static DispatchPreparation ready() {
            return new DispatchPreparation(true, false, null, null, null);
        }

        private static DispatchPreparation skipped() {
            return new DispatchPreparation(false, false, null, null, null);
        }

        private static DispatchPreparation disabled(String eventType,
                Duration attemptDuration, Duration deliveryLatency) {
            return new DispatchPreparation(false, true, eventType, attemptDuration, deliveryLatency);
        }

        private static DispatchPreparation unavailable(String eventType,
                Duration attemptDuration, Duration deliveryLatency) {
            return new DispatchPreparation(false, true, eventType, attemptDuration, deliveryLatency);
        }
    }
}
