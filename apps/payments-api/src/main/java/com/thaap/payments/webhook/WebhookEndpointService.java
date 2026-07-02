package com.thaap.payments.webhook;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookEndpointService {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointSubscriptionStore subscriptionStore;
    private final WebhookSecretCipher secretCipher;
    private final AuditLogService auditLogService;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public WebhookEndpointService(WebhookEndpointRepository endpointRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointSubscriptionStore subscriptionStore,
            WebhookSecretCipher secretCipher, AuditLogService auditLogService,
            WebhookMetrics metrics, Clock clock) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.subscriptionStore = subscriptionStore;
        this.secretCipher = secretCipher;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public EndpointSecretResult create(MerchantContext merchantContext, String url, Set<String> enabledEvents,
            String requestId) {
        Set<WebhookEventType> eventTypes = normalizeEventTypes(enabledEvents);
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
        return new EndpointSecretResult(endpoint, rawSecret, wireValues(eventTypes));
    }

    @Transactional
    public EndpointSecretResult rotateSecret(MerchantContext merchantContext, UUID endpointId, String requestId) {
        WebhookEndpoint endpoint = loadEndpointForUpdate(merchantContext, endpointId);
        if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Disabled webhook endpoints cannot rotate secrets");
        }
        WebhookEndpointStatus beforeStatus = endpoint.getOperationalStatus();
        String beforePrefix = endpoint.getSigningSecretPrefix();
        String rawSecret = WebhookSigningSecret.generate();
        WebhookEncryptedSecret encryptedSecret = secretCipher.encrypt(
                rawSecret, merchantContext.merchantId(), endpointId);
        endpoint.rotateSecret(rawSecret, encryptedSecret);
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
}
