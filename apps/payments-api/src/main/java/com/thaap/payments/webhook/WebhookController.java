package com.thaap.payments.webhook;

import com.thaap.payments.tenant.MerchantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.URL;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private final WebhookEndpointRepository webhookEndpointRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;

    public WebhookController(WebhookEndpointRepository webhookEndpointRepository,
            WebhookDeliveryRepository webhookDeliveryRepository) {
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
    }

    @PostMapping("/webhook_endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointResponse createEndpoint(MerchantContext merchantContext,
            @Valid @RequestBody CreateWebhookEndpointRequest request) {
        String signingSecret = WebhookSigningSecret.generate();
        return WebhookEndpointResponse.from(webhookEndpointRepository.save(new WebhookEndpoint(
                merchantContext.merchantId(), request.url(), signingSecret)), signingSecret);
    }

    @GetMapping("/webhook_deliveries")
    public List<WebhookDeliveryResponse> listDeliveries(MerchantContext merchantContext) {
        return webhookDeliveryRepository.findByMerchantIdOrderByCreatedAtAsc(merchantContext.merchantId()).stream()
                .map(WebhookDeliveryResponse::from)
                .toList();
    }

    public record CreateWebhookEndpointRequest(@NotBlank @URL String url) {
    }

    public record WebhookEndpointResponse(UUID id, UUID merchantId, String url, boolean active,
            String signingSecretPrefix, String signingSecret) {
        static WebhookEndpointResponse from(WebhookEndpoint endpoint, String rawSigningSecret) {
            return new WebhookEndpointResponse(endpoint.getId(), endpoint.getMerchantId(), endpoint.getUrl(),
                    endpoint.isActive(), endpoint.getSigningSecretPrefix(), rawSigningSecret);
        }
    }

    public record WebhookDeliveryResponse(UUID id, UUID merchantId, UUID webhookEndpointId, String eventType,
            UUID resourceId, WebhookDeliveryStatus status, int attemptCount, java.time.Instant nextAttemptAt,
            java.time.Instant lastAttemptAt, java.time.Instant deliveredAt, String lastError) {
        static WebhookDeliveryResponse from(WebhookDelivery delivery) {
            return new WebhookDeliveryResponse(delivery.getId(), delivery.getMerchantId(),
                    delivery.getWebhookEndpointId(), delivery.getEventType(), delivery.getResourceId(),
                    delivery.getStatus(), delivery.getAttemptCount(), delivery.getNextAttemptAt(),
                    delivery.getLastAttemptAt(), delivery.getDeliveredAt(), delivery.getLastError());
        }
    }
}
