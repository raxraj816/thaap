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
        @Index(name = "idx_webhook_deliveries_endpoint", columnList = "webhook_endpoint_id"),
        @Index(name = "idx_webhook_deliveries_due", columnList = "status, next_attempt_at, created_at, id"),
        @Index(name = "idx_webhook_deliveries_expired_lease", columnList = "status, lease_expires_at, id"),
        @Index(name = "idx_webhook_deliveries_merchant_newest", columnList = "merchant_id, created_at, id")
})
public class WebhookDelivery {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "webhook_endpoint_id", nullable = false)
    private UUID webhookEndpointId;

    @Column(name = "webhook_event_id", nullable = false)
    private UUID webhookEventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookDeliveryStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(name = "delivery_generation", nullable = false)
    private int deliveryGeneration;

    @Column(name = "generation_attempt_count", nullable = false)
    private int generationAttemptCount;

    @Column
    private Instant nextAttemptAt;

    @Column
    private Instant lastAttemptAt;

    @Column
    private Instant deliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_code", length = 64)
    private WebhookDeliveryErrorCode lastErrorCode;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected WebhookDelivery() {
    }

    public WebhookDelivery(UUID merchantId, UUID webhookEndpointId, UUID webhookEventId,
            String eventType, UUID resourceId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.webhookEndpointId = webhookEndpointId;
        this.webhookEventId = webhookEventId;
        this.eventType = eventType;
        this.resourceId = resourceId;
        this.status = WebhookDeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.deliveryGeneration = 1;
        this.generationAttemptCount = 0;
        this.createdAt = createdAt;
    }

    public UUID claim(Instant now, Instant leaseExpiry) {
        if (status != WebhookDeliveryStatus.PENDING
                && status != WebhookDeliveryStatus.RETRY_SCHEDULED
                && status != WebhookDeliveryStatus.DELIVERING) {
            throw new IllegalStateException("Webhook delivery cannot be claimed from status " + status);
        }
        this.status = WebhookDeliveryStatus.DELIVERING;
        this.attemptCount += 1;
        this.generationAttemptCount += 1;
        this.lastAttemptAt = now;
        this.nextAttemptAt = null;
        this.lastErrorCode = null;
        this.claimToken = UUID.randomUUID();
        this.leaseExpiresAt = leaseExpiry;
        return claimToken;
    }

    public void succeed(UUID expectedClaimToken, Instant deliveredAt) {
        requireClaim(expectedClaimToken);
        this.status = WebhookDeliveryStatus.SUCCEEDED;
        this.deliveredAt = deliveredAt;
        this.nextAttemptAt = null;
        this.lastErrorCode = null;
        clearClaim();
    }

    public void renewClaim(UUID expectedClaimToken, Instant leaseExpiry) {
        requireClaim(expectedClaimToken);
        this.leaseExpiresAt = leaseExpiry;
    }

    public void scheduleRetry(UUID expectedClaimToken, Instant nextAttemptAt,
            WebhookDeliveryErrorCode errorCode) {
        requireClaim(expectedClaimToken);
        this.status = WebhookDeliveryStatus.RETRY_SCHEDULED;
        this.nextAttemptAt = nextAttemptAt;
        this.lastErrorCode = errorCode;
        clearClaim();
    }

    public void exhaust(UUID expectedClaimToken, WebhookDeliveryErrorCode errorCode) {
        requireClaim(expectedClaimToken);
        this.status = WebhookDeliveryStatus.EXHAUSTED;
        this.nextAttemptAt = null;
        this.lastErrorCode = errorCode;
        clearClaim();
    }

    public void exhaustWithoutClaim(WebhookDeliveryErrorCode errorCode) {
        if (status == WebhookDeliveryStatus.SUCCEEDED || status == WebhookDeliveryStatus.EXHAUSTED) {
            return;
        }
        this.status = WebhookDeliveryStatus.EXHAUSTED;
        this.nextAttemptAt = null;
        this.lastErrorCode = errorCode;
        clearClaim();
    }

    public int scheduleManualRedelivery() {
        if (status != WebhookDeliveryStatus.SUCCEEDED
                && status != WebhookDeliveryStatus.EXHAUSTED) {
            throw new IllegalStateException("Only terminal webhook deliveries can be redelivered");
        }
        this.deliveryGeneration += 1;
        this.generationAttemptCount = 0;
        this.status = WebhookDeliveryStatus.PENDING;
        this.nextAttemptAt = null;
        this.lastAttemptAt = null;
        this.deliveredAt = null;
        this.lastErrorCode = null;
        clearClaim();
        return deliveryGeneration;
    }

    private void requireClaim(UUID expectedClaimToken) {
        if (!matchesClaim(expectedClaimToken)) {
            throw new IllegalStateException("Webhook delivery claim is stale");
        }
    }

    public boolean matchesClaim(UUID expectedClaimToken) {
        return status == WebhookDeliveryStatus.DELIVERING
                && claimToken != null
                && claimToken.equals(expectedClaimToken);
    }

    private void clearClaim() {
        this.claimToken = null;
        this.leaseExpiresAt = null;
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

    public UUID getWebhookEventId() {
        return webhookEventId;
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

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getDeliveryGeneration() {
        return deliveryGeneration;
    }

    public int getGenerationAttemptCount() {
        return generationAttemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public WebhookDeliveryErrorCode getLastErrorCode() {
        return lastErrorCode;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
