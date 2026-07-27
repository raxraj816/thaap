package com.thaap.payments.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_delivery_attempts", indexes = {
        @Index(name = "idx_webhook_attempts_merchant_delivery",
                columnList = "merchant_id, webhook_delivery_id, attempt_number, id")
})
public class WebhookDeliveryAttempt {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "webhook_delivery_id", nullable = false)
    private UUID webhookDeliveryId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "delivery_generation", nullable = false)
    private int deliveryGeneration;

    @Column(name = "generation_attempt_number", nullable = false)
    private int generationAttemptNumber;

    @Column(name = "dispatch_source", nullable = false, length = 32)
    private String dispatchSource;

    @Column(name = "event_payload_sha256", nullable = false, length = 64)
    private String eventPayloadSha256;

    @Column(name = "signing_secret_generation")
    private UUID signingSecretGeneration;

    @Column(name = "redelivery_request_id")
    private UUID redeliveryRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WebhookAttemptOutcome outcome;

    @Column(name = "http_request_sent")
    private Boolean httpRequestSent;

    @Column(name = "dispatch_started_at")
    private Instant dispatchStartedAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 64)
    private WebhookDeliveryErrorCode errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "latency_ms")
    private Long latencyMs;

    protected WebhookDeliveryAttempt() {
    }

    public WebhookDeliveryAttempt(UUID merchantId, UUID webhookDeliveryId, int attemptNumber, Instant startedAt) {
        this(merchantId, webhookDeliveryId, attemptNumber, 1, attemptNumber,
                "AUTOMATIC_RETRY", null, null, null, startedAt);
    }

    public WebhookDeliveryAttempt(UUID merchantId, UUID webhookDeliveryId, int attemptNumber,
            int deliveryGeneration, int generationAttemptNumber, String dispatchSource,
            String eventPayloadSha256, UUID signingSecretGeneration,
            UUID redeliveryRequestId, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.webhookDeliveryId = webhookDeliveryId;
        this.attemptNumber = attemptNumber;
        this.deliveryGeneration = deliveryGeneration;
        this.generationAttemptNumber = generationAttemptNumber;
        this.dispatchSource = dispatchSource;
        this.eventPayloadSha256 = eventPayloadSha256;
        this.signingSecretGeneration = signingSecretGeneration;
        this.redeliveryRequestId = redeliveryRequestId;
        this.outcome = WebhookAttemptOutcome.IN_PROGRESS;
        this.httpRequestSent = false;
        this.startedAt = startedAt;
    }

    public void succeed(Instant completedAt, int responseStatus) {
        complete(completedAt, WebhookAttemptOutcome.SUCCEEDED, true, responseStatus, null);
    }

    public void failRetryably(Instant completedAt, Boolean requestSent, Integer responseStatus,
            WebhookDeliveryErrorCode errorCode) {
        complete(completedAt, WebhookAttemptOutcome.RETRYABLE_FAILURE, requestSent, responseStatus, errorCode);
    }

    public void failTerminally(Instant completedAt, Boolean requestSent, Integer responseStatus,
            WebhookDeliveryErrorCode errorCode) {
        complete(completedAt, WebhookAttemptOutcome.TERMINAL_FAILURE, requestSent, responseStatus, errorCode);
    }

    public void markDispatchStarted(Instant dispatchStartedAt) {
        if (outcome != WebhookAttemptOutcome.IN_PROGRESS || this.dispatchStartedAt != null) {
            throw new IllegalStateException("Webhook attempt cannot start dispatch from its current state");
        }
        this.dispatchStartedAt = dispatchStartedAt;
        this.httpRequestSent = null;
    }

    public void markOutcomeUnknown(Instant completedAt, WebhookDeliveryErrorCode errorCode) {
        if (dispatchStartedAt == null) {
            throw new IllegalStateException("Webhook attempt cannot be ambiguous before dispatch starts");
        }
        complete(completedAt, WebhookAttemptOutcome.OUTCOME_UNKNOWN, null, null, errorCode);
    }

    private void complete(Instant completedAt, WebhookAttemptOutcome outcome, Boolean requestSent,
            Integer responseStatus, WebhookDeliveryErrorCode errorCode) {
        if (this.outcome != WebhookAttemptOutcome.IN_PROGRESS) {
            throw new IllegalStateException("Webhook attempt is already complete");
        }
        this.outcome = outcome;
        this.httpRequestSent = requestSent;
        this.responseStatus = responseStatus;
        this.errorCode = errorCode;
        this.completedAt = completedAt;
        this.latencyMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getWebhookDeliveryId() {
        return webhookDeliveryId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public int getDeliveryGeneration() {
        return deliveryGeneration;
    }

    public int getGenerationAttemptNumber() {
        return generationAttemptNumber;
    }

    public String getDispatchSource() {
        return dispatchSource;
    }

    public String getEventPayloadSha256() {
        return eventPayloadSha256;
    }

    public UUID getSigningSecretGeneration() {
        return signingSecretGeneration;
    }

    public UUID getRedeliveryRequestId() {
        return redeliveryRequestId;
    }

    public WebhookAttemptOutcome getOutcome() {
        return outcome;
    }

    public Boolean getHttpRequestSent() {
        return httpRequestSent;
    }

    public Instant getDispatchStartedAt() {
        return dispatchStartedAt;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public WebhookDeliveryErrorCode getErrorCode() {
        return errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }
}
