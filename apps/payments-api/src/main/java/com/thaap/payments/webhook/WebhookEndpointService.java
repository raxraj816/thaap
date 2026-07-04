package com.thaap.payments.webhook;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookEndpointService {

    private static final Duration CREATION_REPLAY_TTL = Duration.ofHours(24);
    private static final Duration CREATION_RESERVATION_LEASE = Duration.ofSeconds(30);

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookEndpointCreationRecordRepository creationRecordRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointSubscriptionStore subscriptionStore;
    private final WebhookSecretCipher secretCipher;
    private final AuditLogService auditLogService;
    private final WebhookMetrics metrics;
    private final Clock clock;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final TransactionTemplate creationTransaction;
    private final TransactionTemplate reservationTransaction;
    private final Duration secretRotationGrace;

    public WebhookEndpointService(WebhookEndpointRepository endpointRepository,
            WebhookEndpointCreationRecordRepository creationRecordRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointSubscriptionStore subscriptionStore,
            WebhookSecretCipher secretCipher, AuditLogService auditLogService,
            WebhookMetrics metrics, Clock clock, SensitiveValueHasher sensitiveValueHasher,
            PlatformTransactionManager transactionManager,
            @Value("${thaap.webhook.secret-rotation-grace:15m}") Duration secretRotationGrace) {
        if (secretRotationGrace.isZero() || secretRotationGrace.isNegative()) {
            throw new IllegalArgumentException("Webhook secret rotation grace must be positive");
        }
        this.endpointRepository = endpointRepository;
        this.creationRecordRepository = creationRecordRepository;
        this.deliveryRepository = deliveryRepository;
        this.subscriptionStore = subscriptionStore;
        this.secretCipher = secretCipher;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.clock = clock;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.creationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.secretRotationGrace = secretRotationGrace;
    }

    public EndpointSecretResult create(MerchantContext merchantContext, String idempotencyKey,
            String url, Set<String> enabledEvents, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        Set<WebhookEventType> eventTypes = normalizeEventTypes(enabledEvents);
        String requestHash = creationRequestHash(url, eventTypes);
        String idempotencyKeyHash = sensitiveValueHasher.hmacSha256(
                "webhook.endpoint.idempotency", idempotencyKey);
        CreationReservation reservation = reserveCreation(
                merchantContext.merchantId(), idempotencyKeyHash, requestHash);
        if (reservation.replayRecord() != null) {
            return replayCreation(merchantContext, reservation.replayRecord());
        }
        try {
            EndpointSecretResult result = creationTransaction.execute(status ->
                    createOnce(merchantContext, url, eventTypes, requestId,
                            reservation.recordId(), reservation.leaseToken()));
            if (result == null) {
                throw new IllegalStateException("Webhook endpoint creation transaction returned no result");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            releaseCreationReservation(reservation.recordId(), reservation.leaseToken());
            if (isDuplicateEndpointUrl(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A webhook endpoint already exists for this URL", exception);
            }
            throw exception;
        } catch (RuntimeException | Error exception) {
            releaseCreationReservation(reservation.recordId(), reservation.leaseToken());
            throw exception;
        }
    }

    EndpointSecretResult create(MerchantContext merchantContext, String url,
            Set<String> enabledEvents, String requestId) {
        return create(merchantContext, "service:" + requestId, url, enabledEvents, requestId);
    }

    private EndpointSecretResult createOnce(MerchantContext merchantContext, String url,
            Set<WebhookEventType> eventTypes, String requestId, UUID creationRecordId,
            UUID leaseToken) {
        UUID endpointId = UUID.randomUUID();
        String rawSecret = WebhookSigningSecret.generate();
        WebhookEncryptedSecret encryptedSecret = secretCipher.encrypt(
                rawSecret, merchantContext.merchantId(), endpointId);
        Instant now = clock.instant();
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(new WebhookEndpoint(endpointId,
                merchantContext.merchantId(), url, rawSecret, encryptedSecret, now));
        subscriptionStore.insertAll(merchantContext.merchantId(), endpointId, eventTypes, now);
        auditLogService.recordMerchantApiAction(merchantContext, requestId, "webhook_endpoint.created",
                "webhook_endpoint", endpointId, "Webhook endpoint created",
                null, endpointAuditState(endpoint, wireValues(eventTypes)));
        WebhookEndpointCreationRecord record = creationRecordRepository.findByIdForUpdate(creationRecordId)
                .orElseThrow(() -> new IllegalStateException(
                        "Webhook endpoint creation reservation disappeared"));
        record.complete(leaseToken, endpointId, endpoint.getSigningSecretGeneration(),
                new WebhookEncryptedSecret(
                        endpoint.getSigningSecretCiphertext(),
                        endpoint.getSigningSecretNonce(),
                        endpoint.getSigningSecretKeyVersion()),
                now, now.plus(CREATION_REPLAY_TTL));
        return new EndpointSecretResult(endpoint, rawSecret, wireValues(eventTypes));
    }

    private CreationReservation reserveCreation(UUID merchantId,
            String idempotencyKeyHash, String requestHash) {
        try {
            return reservationTransaction.execute(status ->
                    reserveCreationOnce(merchantId, idempotencyKeyHash, requestHash));
        } catch (DataIntegrityViolationException exception) {
            return reservationTransaction.execute(status ->
                    existingCreation(merchantId, idempotencyKeyHash, requestHash));
        }
    }

    private CreationReservation reserveCreationOnce(UUID merchantId,
            String idempotencyKeyHash, String requestHash) {
        return creationRecordRepository.findByMerchantIdAndIdempotencyKeyHashForUpdate(
                        merchantId, idempotencyKeyHash)
                .map(record -> existingCreation(record, requestHash))
                .orElseGet(() -> {
                    Instant now = clock.instant();
                    UUID leaseToken = UUID.randomUUID();
                    WebhookEndpointCreationRecord record = new WebhookEndpointCreationRecord(
                            merchantId, idempotencyKeyHash, requestHash,
                            leaseToken, now.plus(CREATION_RESERVATION_LEASE),
                            now, now.plus(CREATION_REPLAY_TTL));
                    creationRecordRepository.saveAndFlush(record);
                    return CreationReservation.reserved(record.getId(), leaseToken);
                });
    }

    private CreationReservation existingCreation(UUID merchantId,
            String idempotencyKeyHash, String requestHash) {
        WebhookEndpointCreationRecord record = creationRecordRepository
                .findByMerchantIdAndIdempotencyKeyHashForUpdate(
                        merchantId, idempotencyKeyHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Webhook endpoint creation reservation could not be completed"));
        return existingCreation(record, requestHash);
    }

    private CreationReservation existingCreation(WebhookEndpointCreationRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different webhook endpoint request");
        }
        if (record.getStatus() != WebhookEndpointCreationStatus.COMPLETED) {
            Instant now = clock.instant();
            if (record.hasActiveLease(now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Webhook endpoint creation is already in progress");
            }
            UUID leaseToken = UUID.randomUUID();
            record.reclaim(leaseToken, now, now.plus(CREATION_RESERVATION_LEASE));
            return CreationReservation.reserved(record.getId(), leaseToken);
        }
        return CreationReservation.replay(record);
    }

    private EndpointSecretResult replayCreation(MerchantContext merchantContext,
            WebhookEndpointCreationRecord record) {
        if (record.getExpiresAt().isBefore(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Webhook endpoint creation replay window has expired");
        }
        UUID endpointId = record.getWebhookEndpointId();
        WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantId(
                        endpointId, merchantContext.merchantId())
                .orElseThrow(() -> new IllegalStateException(
                        "Completed webhook endpoint creation lost endpoint lineage"));
        if (!endpoint.getSigningSecretGeneration().equals(
                record.getSigningSecretGeneration())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Webhook endpoint creation response was superseded by secret rotation");
        }
        String rawSecret = secretCipher.decrypt(
                record.encryptedSecret(), merchantContext.merchantId(), endpointId);
        return new EndpointSecretResult(endpoint, rawSecret,
                subscriptionStore.findWireValues(merchantContext.merchantId(), endpointId));
    }

    private void releaseCreationReservation(UUID recordId, UUID leaseToken) {
        reservationTransaction.executeWithoutResult(status -> creationRecordRepository.findByIdForUpdate(recordId)
                .filter(record -> record.isOwnedInProgress(leaseToken))
                .ifPresent(creationRecordRepository::delete));
    }

    private String creationRequestHash(String url, Set<WebhookEventType> eventTypes) {
        String canonicalEvents = eventTypes.stream()
                .map(WebhookEventType::wireValue)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        return sensitiveValueHasher.hmacSha256(
                "webhook.endpoint.create", url + "\n" + canonicalEvents);
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing Idempotency-Key header");
        }
        if (idempotencyKey.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain at most 255 characters");
        }
    }

    private boolean isDuplicateEndpointUrl(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null
                && message.toLowerCase(java.util.Locale.ROOT)
                        .contains("uk_webhook_endpoints_merchant_url");
    }

    @Transactional
    public EndpointSecretResult rotateSecret(MerchantContext merchantContext, UUID endpointId, String requestId) {
        WebhookEndpoint endpoint = loadEndpointForUpdate(merchantContext, endpointId);
        if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Disabled webhook endpoints cannot rotate secrets");
        }
        WebhookEndpointStatus beforeStatus = endpoint.getOperationalStatus();
        Instant rotatedAt = clock.instant();
        if (endpoint.hasActivePreviousSecret(rotatedAt)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Webhook endpoint already has an active previous-secret overlap");
        }
        String beforePrefix = endpoint.getSigningSecretPrefix();
        String rawSecret = WebhookSigningSecret.generate();
        WebhookEncryptedSecret encryptedSecret = secretCipher.encrypt(
                rawSecret, merchantContext.merchantId(), endpointId);
        endpoint.rotateSecret(rawSecret, encryptedSecret, rotatedAt,
                rotatedAt.plus(secretRotationGrace));
        Set<String> enabledEvents = subscriptionStore.findWireValues(merchantContext.merchantId(), endpointId);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", beforeStatus.name());
        before.put("signingSecretPrefix", beforePrefix);
        auditLogService.recordMerchantApiAction(merchantContext, requestId, "webhook_endpoint.secret_rotated",
                "webhook_endpoint", endpointId, "Webhook signing secret rotated",
                before, endpointAuditState(endpoint, enabledEvents));
        return new EndpointSecretResult(endpoint, rawSecret, enabledEvents);
    }

    @Transactional
    public EndpointStateResult disable(MerchantContext merchantContext, UUID endpointId, String requestId) {
        WebhookEndpoint endpoint = loadEndpointForUpdate(merchantContext, endpointId);
        WebhookEndpointStatus beforeStatus = endpoint.getOperationalStatus();
        Set<String> enabledEvents = subscriptionStore.findWireValues(
                merchantContext.merchantId(), endpointId);
        endpoint.disable();
        endpointRepository.flush();
        List<DisableMetricObservation> disabledDeliveries = new ArrayList<>();
        int queuedDeliveriesExhausted = 0;
        for (WebhookEventType eventType : WebhookEventType.values()) {
            int eventTypeExhausted = deliveryRepository.exhaustQueuedForDisabledEndpointByEventType(
                    merchantContext.merchantId(), endpointId, eventType.wireValue(),
                    List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRY_SCHEDULED),
                    WebhookDeliveryStatus.EXHAUSTED, WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
            queuedDeliveriesExhausted += eventTypeExhausted;
            if (eventTypeExhausted > 0) {
                disabledDeliveries.add(new DisableMetricObservation(
                        eventType.wireValue(), eventTypeExhausted));
            }
        }

        auditLogService.recordMerchantApiAction(merchantContext, requestId, "webhook_endpoint.disabled",
                "webhook_endpoint", endpointId, "Webhook endpoint disabled",
                Map.of("status", beforeStatus.name()),
                Map.of("status", endpoint.getOperationalStatus().name(),
                        "queuedDeliveriesExhausted", queuedDeliveriesExhausted));
        recordDisableMetricsAfterCommit(List.copyOf(disabledDeliveries));
        return new EndpointStateResult(endpoint, enabledEvents);
    }

    public WebhookEndpoint loadEndpoint(MerchantContext merchantContext, UUID endpointId) {
        return endpointRepository.findByIdAndMerchantId(endpointId, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Webhook endpoint was not found"));
    }

    private WebhookEndpoint loadEndpointForUpdate(MerchantContext merchantContext, UUID endpointId) {
        return endpointRepository.findByIdAndMerchantIdForUpdate(
                        endpointId, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Webhook endpoint was not found"));
    }

    private void recordDisableMetricsAfterCommit(List<DisableMetricObservation> counts) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (DisableMetricObservation count : counts) {
                    metrics.recordTerminalCount(count.eventType(),
                            WebhookDeliveryStatus.EXHAUSTED, count.deliveryCount());
                    metrics.recordFailureCount(count.eventType(),
                            WebhookDeliveryErrorCode.ENDPOINT_DISABLED, count.deliveryCount());
                }
            }
        });
    }

    private Set<WebhookEventType> normalizeEventTypes(Set<String> enabledEvents) {
        if (enabledEvents == null) {
            return new LinkedHashSet<>(Arrays.asList(WebhookEventType.values()));
        }
        if (enabledEvents.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "enabledEvents must contain at least one supported event type");
        }
        Set<WebhookEventType> normalized = new LinkedHashSet<>();
        try {
            for (String enabledEvent : enabledEvents) {
                normalized.add(WebhookEventType.fromWireValue(enabledEvent));
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        return normalized;
    }

    private Set<String> wireValues(Set<WebhookEventType> eventTypes) {
        Set<String> values = new LinkedHashSet<>();
        for (WebhookEventType eventType : eventTypes) {
            values.add(eventType.wireValue());
        }
        return values;
    }

    private Map<String, Object> endpointAuditState(WebhookEndpoint endpoint, Set<?> eventTypes) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("endpointId", endpoint.getId().toString());
        state.put("status", endpoint.getOperationalStatus().name());
        state.put("signingSecretPrefix", endpoint.getSigningSecretPrefix());
        state.put("enabledEvents", eventTypes.stream().map(Object::toString).sorted().toList());
        return state;
    }

    public record EndpointSecretResult(WebhookEndpoint endpoint, String rawSigningSecret,
            Set<String> enabledEvents) {
    }

    public record EndpointStateResult(WebhookEndpoint endpoint, Set<String> enabledEvents) {
    }

    private record DisableMetricObservation(String eventType, long deliveryCount) {
    }

    private record CreationReservation(UUID recordId, UUID leaseToken,
            WebhookEndpointCreationRecord replayRecord) {

        private static CreationReservation reserved(UUID recordId, UUID leaseToken) {
            return new CreationReservation(recordId, leaseToken, null);
        }

        private static CreationReservation replay(WebhookEndpointCreationRecord record) {
            return new CreationReservation(record.getId(), null, record);
        }
    }
}
