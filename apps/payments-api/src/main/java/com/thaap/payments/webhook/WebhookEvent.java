package com.thaap.payments.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_events", indexes = {
        @Index(name = "idx_webhook_events_merchant_created", columnList = "merchant_id, created_at, id")
})
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    @Column(name = "api_version", nullable = false, length = 16)
    private String apiVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(UUID id, UUID merchantId, WebhookEventType eventType, UUID resourceId, String payload,
            String payloadSha256, Instant occurredAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.eventType = eventType.wireValue();
        this.resourceId = resourceId;
        this.resourceType = eventType.resourceType().name();
        this.apiVersion = "v1";
        this.payload = payload;
        this.payloadSha256 = payloadSha256;
        this.occurredAt = occurredAt;
        this.createdAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
