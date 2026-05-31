package com.thaap.payments.webhook;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WebhookDeliveryService {

    private final WebhookEndpointRepository webhookEndpointRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;

    public WebhookDeliveryService(WebhookEndpointRepository webhookEndpointRepository,
            WebhookDeliveryRepository webhookDeliveryRepository) {
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
    }

    public void createDeliveries(UUID merchantId, String eventType, UUID resourceId) {
        webhookEndpointRepository.findByMerchantIdAndActiveTrue(merchantId)
                .forEach(endpoint -> webhookDeliveryRepository.save(new WebhookDelivery(merchantId, endpoint.getId(),
                        eventType, resourceId)));
    }
}
