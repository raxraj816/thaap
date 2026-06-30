package com.thaap.payments.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookDeliveryService {

    private final WebhookEndpointRepository webhookEndpointRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookEventEnvelopeFactory envelopeFactory;
    private final Clock clock;

    public WebhookDeliveryService(WebhookEndpointRepository webhookEndpointRepository,
            WebhookEventRepository webhookEventRepository,
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookEventEnvelopeFactory envelopeFactory, Clock clock) {
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.envelopeFactory = envelopeFactory;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createDeliveries(UUID merchantId, String eventType, UUID resourceId) {
        WebhookEventType canonicalEventType = WebhookEventType.fromWireValue(eventType);
        List<String> endpointIdValues = webhookEndpointRepository.findActiveSubscribedEndpointIds(
                merchantId, canonicalEventType.wireValue());
        if (endpointIdValues.isEmpty()) {
            return;
        }
        List<UUID> endpointIds = endpointIdValues.stream().map(UUID::fromString).toList();
        List<WebhookEndpoint> endpoints = webhookEndpointRepository.lockActiveEndpoints(
                merchantId, endpointIds);
        if (endpoints.isEmpty()) {
            return;
        }
        WebhookEvent event = webhookEventRepository.save(
                envelopeFactory.create(merchantId, canonicalEventType, resourceId, clock.instant()));
        Instant occurredAt = event.getOccurredAt();
        for (WebhookEndpoint endpoint : endpoints) {
            webhookDeliveryRepository.save(new WebhookDelivery(merchantId, endpoint.getId(), event.getId(),
                    canonicalEventType.wireValue(), resourceId, occurredAt));
        }
    }
}
