package com.thaap.payments.webhook;

import com.thaap.payments.risk.RiskOperationsEventProducer;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final RiskOperationsEventProducer eventProducer;

    public WebhookDeliveryClaimService(JdbcTemplate jdbcTemplate,
            WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookEndpointRepository endpointRepository,
            WebhookEventRepository eventRepository, WebhookMetrics metrics,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            @Value("${thaap.webhook.lease-duration:30s}") Duration leaseDuration,
            RiskOperationsEventProducer eventProducer) {
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
        this.eventProducer = eventProducer;
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.claimTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<WebhookDeliveryClaim> claimDue(Instant now, int batchSize) {
        return claimDue(now, batchSize, Set.of(), Set.of());
    }

    public List<WebhookDeliveryClaim> claimDue(Instant now, int batchSize,
            Set<UUID> excludedMerchantIds, Set<UUID> excludedEndpointIds) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Webhook claim batch size must be between 1 and 100");
        }
        Set<UUID> merchantExclusions = excludedMerchantIds == null
                ? Set.of()
                : Set.copyOf(excludedMerchantIds);
        Set<UUID> endpointExclusions = excludedEndpointIds == null
                ? Set.of()
                : Set.copyOf(excludedEndpointIds);
        ClaimBatch batch = claimTransaction.execute(
                status -> claimDueInTransaction(
                        now, batchSize, merchantExclusions, endpointExclusions));
        if (batch == null) {
            return List.of();
        }
        for (RecoveryObservation recovery : batch.recoveries()) {
            metrics.recordAttempt(recovery.eventType(),
                    recovery.outcome().name(), recovery.attemptDuration());
            metrics.recordFailure(recovery.eventType(), recovery.errorCode());
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
                eventProducer.webhookDeliveryExhausted(claim.merchantId(), claim.endpointId(), now,
                        delivery.getDeliveryGeneration(), delivery.getAttemptCount(),
                        WebhookDeliveryErrorCode.ENDPOINT_DISABLED.name());
                return DispatchPreparation.disabled(claim.eventType(),
                        Duration.between(attempt.getStartedAt(), now),
                        Duration.between(delivery.getCreatedAt(), now));
            }
            if (endpoint.getOperationalStatus() != WebhookEndpointStatus.ACTIVE) {
                attempt.failTerminally(now, false, null,
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
                delivery.exhaust(claim.claimToken(),
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
                eventProducer.webhookDeliveryExhausted(claim.merchantId(), claim.endpointId(), now,
                        delivery.getDeliveryGeneration(), delivery.getAttemptCount(),
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE.name());
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
            metrics.recordFailure(preparation.eventType(), preparation.errorCode());
            metrics.recordTerminal(preparation.eventType(), WebhookDeliveryStatus.EXHAUSTED,
                    preparation.deliveryLatency());
        }
        return preparation.prepared();
    }

    private ClaimBatch claimDueInTransaction(Instant now, int batchSize,
            Set<UUID> excludedMerchantIds, Set<UUID> excludedEndpointIds) {
        DispatchCursor cursor = loadDispatchCursorForUpdate();
        Set<UUID> selectedDeliveryIds = new LinkedHashSet<>();
        UUID lastMerchantId = cursor.lastMerchantId();
        UUID lastEndpointId = cursor.lastEndpointId();
        for (int index = 0; index < batchSize; index++) {
            FairCandidate candidate = findFairCandidate(now, lastMerchantId, lastEndpointId,
                    excludedMerchantIds, excludedEndpointIds, selectedDeliveryIds);
            if (candidate == null) {
                break;
            }
            selectedDeliveryIds.add(candidate.deliveryId());
            lastMerchantId = candidate.merchantId();
            lastEndpointId = candidate.endpointId();
        }
        if (!selectedDeliveryIds.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE webhook_dispatch_cursor
                    SET last_merchant_id = ?,
                        last_endpoint_id = ?,
                        updated_at = ?
                    WHERE singleton_id = 1
                    """, lastMerchantId, lastEndpointId, Timestamp.from(now));
        }

        List<WebhookDeliveryClaim> claims = new ArrayList<>();
        List<RecoveryObservation> recoveries = new ArrayList<>();
        for (UUID deliveryId : selectedDeliveryIds) {
            WebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Claimed webhook delivery disappeared"));
            WebhookEndpoint endpoint = endpointRepository.findById(delivery.getWebhookEndpointId())
                    .orElseThrow(() -> new IllegalStateException("Webhook endpoint lineage disappeared"));
            if (delivery.getStatus() == WebhookDeliveryStatus.DELIVERING) {
                WebhookDeliveryAttempt expiredAttempt = closeExpiredAttempt(delivery, now);
                boolean attemptsExhausted =
                        delivery.getGenerationAttemptCount() >= WebhookRetryPolicy.MAX_ATTEMPTS;
                boolean endpointDisabled =
                        endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED;
                recoveries.add(new RecoveryObservation(delivery.getEventType(),
                        expiredAttempt.getOutcome(),
                        endpointDisabled
                                ? WebhookDeliveryErrorCode.ENDPOINT_DISABLED
                                : WebhookDeliveryErrorCode.LEASE_EXPIRED,
                        Duration.between(expiredAttempt.getStartedAt(), now),
                        Duration.between(delivery.getCreatedAt(), now),
                        attemptsExhausted || endpointDisabled));
                if (endpointDisabled) {
                    delivery.exhaust(delivery.getClaimToken(),
                            WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
                    eventProducer.webhookDeliveryExhausted(delivery.getMerchantId(), endpoint.getId(), now,
                            delivery.getDeliveryGeneration(), delivery.getAttemptCount(),
                            WebhookDeliveryErrorCode.ENDPOINT_DISABLED.name());
                    continue;
                }
                if (attemptsExhausted) {
                    delivery.exhaust(delivery.getClaimToken(), WebhookDeliveryErrorCode.LEASE_EXPIRED);
                    eventProducer.webhookDeliveryExhausted(delivery.getMerchantId(), endpoint.getId(), now,
                            delivery.getDeliveryGeneration(), delivery.getAttemptCount(),
                            WebhookDeliveryErrorCode.LEASE_EXPIRED.name());
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
            int deliveryGeneration = delivery.getDeliveryGeneration();
            UUID redeliveryRequestId = deliveryGeneration == 1 ? null : requireRedeliveryRequestId(
                    delivery.getMerchantId(), delivery.getId(), deliveryGeneration);
            UUID claimToken = delivery.claim(now, now.plus(leaseDuration));
            WebhookDeliveryAttempt attempt = attemptRepository.save(new WebhookDeliveryAttempt(
                    delivery.getMerchantId(), delivery.getId(), delivery.getAttemptCount(),
                    deliveryGeneration, delivery.getGenerationAttemptCount(),
                    deliveryGeneration == 1 ? "AUTOMATIC_RETRY" : "MANUAL_REDELIVERY",
                    event.getPayloadSha256(), endpoint.getSigningSecretGeneration(),
                    redeliveryRequestId, now));
            claims.add(new WebhookDeliveryClaim(delivery.getId(), delivery.getMerchantId(),
                    endpoint.getId(), event.getId(), event.getEventType(), endpoint.getUrl(),
                    event.getPayload(), event.getPayloadSha256(), claimToken,
                    attempt.getAttemptNumber(), endpoint.getSigningSecretGeneration(), now,
                    delivery.getCreatedAt(), deliveryGeneration,
                    delivery.getGenerationAttemptCount(), redeliveryRequestId));
        }
        entityManager.flush();
        return new ClaimBatch(List.copyOf(claims), List.copyOf(recoveries));
    }

    private DispatchCursor loadDispatchCursorForUpdate() {
        return jdbcTemplate.queryForObject("""
                SELECT last_merchant_id, last_endpoint_id
                FROM webhook_dispatch_cursor
                WHERE singleton_id = 1
                FOR UPDATE
                """, (resultSet, rowNumber) -> new DispatchCursor(
                        nullableUuid(resultSet.getObject(1)),
                        nullableUuid(resultSet.getObject(2))));
    }

    private FairCandidate findFairCandidate(Instant now, UUID lastMerchantId,
            UUID lastEndpointId, Set<UUID> excludedMerchantIds,
            Set<UUID> excludedEndpointIds, Set<UUID> selectedDeliveryIds) {
        List<UUID> merchants = findEligibleMerchants(
                now, lastMerchantId, excludedMerchantIds, excludedEndpointIds);
        for (UUID merchantId : merchants) {
            List<UUID> endpoints = findEligibleEndpoints(
                    now, merchantId, lastEndpointId, excludedEndpointIds);
            for (UUID endpointId : endpoints) {
                UUID deliveryId = findOldestUnlockedDelivery(
                        now, merchantId, endpointId, selectedDeliveryIds);
                if (deliveryId != null) {
                    return new FairCandidate(deliveryId, merchantId, endpointId);
                }
            }
        }
        return null;
    }

    private List<UUID> findEligibleMerchants(Instant now, UUID lastMerchantId,
            Set<UUID> excludedMerchantIds, Set<UUID> excludedEndpointIds) {
        String merchantExclusion = notInClause("delivery.merchant_id", excludedMerchantIds.size());
        String endpointExclusion = notInClause(
                "delivery.webhook_endpoint_id", excludedEndpointIds.size());
        String cursorOrder = lastMerchantId == null
                ? "eligible.merchant_id"
                : "CASE WHEN eligible.merchant_id > ? THEN 0 ELSE 1 END, eligible.merchant_id";
        String sql = """
                WITH eligible AS (
                    SELECT DISTINCT delivery.merchant_id
                    FROM webhook_deliveries delivery
                    WHERE %s
                      %s
                      %s
                )
                SELECT eligible.merchant_id
                FROM eligible
                ORDER BY %s
                """.formatted(duePredicate("delivery"), merchantExclusion,
                endpointExclusion, cursorOrder);
        List<Object> parameters = dueParameters(now);
        parameters.addAll(excludedMerchantIds);
        parameters.addAll(excludedEndpointIds);
        if (lastMerchantId != null) {
            parameters.add(lastMerchantId);
        }
        return queryUuids(sql, parameters, 100);
    }

    private List<UUID> findEligibleEndpoints(Instant now, UUID merchantId,
            UUID lastEndpointId, Set<UUID> excludedEndpointIds) {
        String endpointExclusion = notInClause(
                "delivery.webhook_endpoint_id", excludedEndpointIds.size());
        String cursorOrder = lastEndpointId == null
                ? "eligible.webhook_endpoint_id"
                : "CASE WHEN eligible.webhook_endpoint_id > ? THEN 0 ELSE 1 END, "
                        + "eligible.webhook_endpoint_id";
        String sql = """
                WITH eligible AS (
                    SELECT DISTINCT delivery.webhook_endpoint_id
                    FROM webhook_deliveries delivery
                    WHERE delivery.merchant_id = ?
                      AND %s
                      %s
                )
                SELECT eligible.webhook_endpoint_id
                FROM eligible
                ORDER BY %s
                """.formatted(duePredicate("delivery"), endpointExclusion, cursorOrder);
        List<Object> parameters = new ArrayList<>();
        parameters.add(merchantId);
        parameters.addAll(dueParameters(now));
        parameters.addAll(excludedEndpointIds);
        if (lastEndpointId != null) {
            parameters.add(lastEndpointId);
        }
        return queryUuids(sql, parameters, 100);
    }

    private UUID findOldestUnlockedDelivery(Instant now, UUID merchantId,
            UUID endpointId, Set<UUID> selectedDeliveryIds) {
        String deliveryExclusion = notInClause("delivery.id", selectedDeliveryIds.size());
        String sql = """
                SELECT delivery.id
                FROM webhook_deliveries delivery
                WHERE delivery.merchant_id = ?
                  AND delivery.webhook_endpoint_id = ?
                  AND %s
                  %s
                ORDER BY
                    COALESCE(delivery.next_attempt_at, delivery.created_at),
                    delivery.created_at,
                    delivery.id
                FOR UPDATE SKIP LOCKED
                """.formatted(duePredicate("delivery"), deliveryExclusion);
        List<Object> parameters = new ArrayList<>();
        parameters.add(merchantId);
        parameters.add(endpointId);
        parameters.addAll(dueParameters(now));
        parameters.addAll(selectedDeliveryIds);
        List<UUID> deliveryIds = queryUuids(sql, parameters, 1);
        return deliveryIds.isEmpty() ? null : deliveryIds.getFirst();
    }

    private String duePredicate(String alias) {
        return """
                (
                    (
                        %1$s.status = 'DELIVERING'
                        AND %1$s.lease_expires_at <= ?
                    )
                    OR (
                        EXISTS (
                            SELECT 1
                            FROM webhook_endpoints endpoint
                            WHERE endpoint.merchant_id = %1$s.merchant_id
                              AND endpoint.id = %1$s.webhook_endpoint_id
                              AND endpoint.operational_status = 'ACTIVE'
                        )
                        AND (
                            %1$s.status = 'PENDING'
                            OR (
                                %1$s.status = 'RETRY_SCHEDULED'
                                AND %1$s.next_attempt_at <= ?
                            )
                        )
                    )
                )
                """.formatted(alias);
    }

    private List<Object> dueParameters(Instant now) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(now));
        parameters.add(Timestamp.from(now));
        return parameters;
    }

    private String notInClause(String column, int valueCount) {
        if (valueCount == 0) {
            return "";
        }
        return "AND " + column + " NOT IN ("
                + String.join(", ", java.util.Collections.nCopies(valueCount, "?")) + ")";
    }

    private List<UUID> queryUuids(String sql, List<Object> parameters, int maxRows) {
        return jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            statement.setMaxRows(maxRows);
            return statement;
        }, (resultSet, rowNumber) -> nullableUuid(resultSet.getObject(1)));
    }

    private UUID nullableUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private WebhookDeliveryAttempt closeExpiredAttempt(WebhookDelivery delivery, Instant now) {
        WebhookDeliveryAttempt attempt = attemptRepository.findByWebhookDeliveryIdAndAttemptNumberForUpdate(
                        delivery.getId(), delivery.getAttemptCount())
                .orElseThrow(() -> new IllegalStateException(
                        "Expired webhook delivery lease has no attempt evidence"));
        if (attempt.getDispatchStartedAt() == null) {
            if (delivery.getGenerationAttemptCount() >= WebhookRetryPolicy.MAX_ATTEMPTS) {
                attempt.failTerminally(now, false, null, WebhookDeliveryErrorCode.LEASE_EXPIRED);
            } else {
                attempt.failRetryably(now, false, null, WebhookDeliveryErrorCode.LEASE_EXPIRED);
            }
        } else {
            attempt.markOutcomeUnknown(now, WebhookDeliveryErrorCode.LEASE_EXPIRED);
        }
        return attempt;
    }

    private UUID requireRedeliveryRequestId(UUID merchantId, UUID deliveryId, int generation) {
        List<UUID> requestIds = jdbcTemplate.queryForList("""
                SELECT id FROM webhook_redelivery_requests
                 WHERE merchant_id = ? AND webhook_delivery_id = ?
                   AND requested_generation = ?
                """, UUID.class, merchantId, deliveryId, generation);
        if (requestIds.size() != 1) {
            throw new IllegalStateException("Manual webhook generation has no redelivery request evidence");
        }
        return requestIds.getFirst();
    }

    private record ClaimBatch(
            List<WebhookDeliveryClaim> claims,
            List<RecoveryObservation> recoveries) {
    }

    private record DispatchCursor(UUID lastMerchantId, UUID lastEndpointId) {
    }

    private record FairCandidate(UUID deliveryId, UUID merchantId, UUID endpointId) {
    }

    private record RecoveryObservation(
            String eventType,
            WebhookAttemptOutcome outcome,
            WebhookDeliveryErrorCode errorCode,
            Duration attemptDuration,
            Duration deliveryLatency,
            boolean exhausted) {
    }

    private record DispatchPreparation(
            boolean prepared,
            boolean terminal,
            String eventType,
            WebhookDeliveryErrorCode errorCode,
            Duration attemptDuration,
            Duration deliveryLatency) {

        private static DispatchPreparation ready() {
            return new DispatchPreparation(true, false, null, null, null, null);
        }

        private static DispatchPreparation skipped() {
            return new DispatchPreparation(false, false, null, null, null, null);
        }

        private static DispatchPreparation disabled(String eventType,
                Duration attemptDuration, Duration deliveryLatency) {
            return new DispatchPreparation(false, true, eventType,
                    WebhookDeliveryErrorCode.ENDPOINT_DISABLED, attemptDuration, deliveryLatency);
        }

        private static DispatchPreparation unavailable(String eventType,
                Duration attemptDuration, Duration deliveryLatency) {
            return new DispatchPreparation(false, true, eventType,
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE, attemptDuration, deliveryLatency);
        }
    }
}
