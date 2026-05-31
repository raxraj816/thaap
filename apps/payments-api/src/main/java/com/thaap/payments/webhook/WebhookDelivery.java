package com.thaap.payments.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries", indexes = {
        @Index(name = "idx_webhook_deliveries_merchant", columnList = "merchant_id"),
        @Index(name = "idx_webhook_deliveries_endpoint", columnList = "webhook_endpoint_id")
})
public class WebhookDelivery {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "webhook_endpoint_id", nullable = false)
    private UUID webhookEndpointId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookDeliveryStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    protected WebhookDelivery() {
    }

    public WebhookDelivery(UUID merchantId, UUID webhookEndpointId, String eventType, UUID resourceId) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.webhookEndpointId = webhookEndpointId;
        this.eventType = eventType;
        this.resourceId = resourceId;
        this.status = WebhookDeliveryStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getWebhookEndpointId() {
        return webhookEndpointId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public WebhookDeliveryStatus getStatus() {
        return status;
    }
}
