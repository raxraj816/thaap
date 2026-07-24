package com.thaap.payments.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import com.thaap.payments.tenant.TrafficOrigin;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Entity
@Table(name = "payment_intents", indexes = {
        @Index(name = "idx_payment_intents_merchant", columnList = "merchant_id"),
        @Index(name = "idx_payment_intents_customer", columnList = "customer_id")
})
public class PaymentIntent {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaptureMethod captureMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentIntentStatus status;

    @Column(length = 500)
    private String description;

    @Convert(converter = PaymentIntentMetadataConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, String> metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    public PaymentIntent(UUID merchantId, UUID customerId, long amount, String currency) {
        this(merchantId, customerId, amount, currency, CaptureMethod.AUTOMATIC, null, Map.of());
    }

    public PaymentIntent(UUID merchantId, UUID customerId, long amount, String currency, CaptureMethod captureMethod) {
        this(merchantId, customerId, amount, currency, captureMethod, null, Map.of());
    }

    public PaymentIntent(UUID merchantId, UUID customerId, long amount, String currency, CaptureMethod captureMethod,
            String description, Map<String, String> metadata) {
        this(merchantId, customerId, amount, currency, captureMethod, description, metadata,
                TrafficOrigin.MERCHANT_API);
    }

    public PaymentIntent(UUID merchantId, UUID customerId, long amount, String currency, CaptureMethod captureMethod,
            String description, Map<String, String> metadata, TrafficOrigin trafficOrigin) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency.toUpperCase();
        this.captureMethod = captureMethod;
        this.status = PaymentIntentStatus.REQUIRES_CONFIRMATION;
        this.description = description;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.trafficOrigin = trafficOrigin;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public CaptureMethod getCaptureMethod() {
        return captureMethod;
    }

    public PaymentIntentStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public TrafficOrigin getTrafficOrigin() {
        return trafficOrigin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markProcessingAfterConfirm() {
        if (status != PaymentIntentStatus.REQUIRES_CONFIRMATION
                && status != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be processed from status " + status);
        }
        this.status = PaymentIntentStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markBlockedAfterFraudDecision() {
        requireConfirmable("blocked by fraud");
        this.status = PaymentIntentStatus.BLOCKED;
        this.updatedAt = Instant.now();
    }

    public void markRequiresActionAfterFraudDecision() {
        requireConfirmable("require fraud step-up");
        this.status = PaymentIntentStatus.REQUIRES_ACTION;
        this.updatedAt = Instant.now();
    }

    public void markRequiresReviewAfterFraudDecision() {
        requireConfirmable("require review");
        this.status = PaymentIntentStatus.REQUIRES_REVIEW;
        this.updatedAt = Instant.now();
    }

    public void markRequiresCaptureAfterAuthorization() {
        if (status != PaymentIntentStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be authorization-approved from status " + status);
        }
        this.status = PaymentIntentStatus.REQUIRES_CAPTURE;
        this.updatedAt = Instant.now();
    }

    public void markProcessingAfterReviewApproval() {
        if (status != PaymentIntentStatus.REQUIRES_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot continue after review from status " + status);
        }
        this.status = PaymentIntentStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markRequiresPaymentMethodAfterProcessorDecline() {
        if (status != PaymentIntentStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be processor-declined from status " + status);
        }
        this.status = PaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
        this.updatedAt = Instant.now();
    }

    public void markRequiresActionAfterProcessorStepUp() {
        if (status != PaymentIntentStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot require action from status " + status);
        }
        this.status = PaymentIntentStatus.REQUIRES_ACTION;
        this.updatedAt = Instant.now();
    }

    public void markProcessingAfterAuthentication() {
        if (status != PaymentIntentStatus.REQUIRES_ACTION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot continue authentication from status " + status);
        }
        status = PaymentIntentStatus.PROCESSING;
        updatedAt = Instant.now();
    }

    public void markRequiresPaymentMethodAfterAuthenticationFailure() {
        if (status != PaymentIntentStatus.REQUIRES_ACTION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot fail authentication from status " + status);
        }
        status = PaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
        updatedAt = Instant.now();
    }

    public void capture() {
        if (status != PaymentIntentStatus.REQUIRES_CAPTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be captured from status " + status);
        }
        this.status = PaymentIntentStatus.CAPTURED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        if (status != PaymentIntentStatus.REQUIRES_CONFIRMATION
                && status != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                && status != PaymentIntentStatus.REQUIRES_ACTION
                && status != PaymentIntentStatus.REQUIRES_FRAUD_CHECK
                && status != PaymentIntentStatus.REQUIRES_REVIEW
                && status != PaymentIntentStatus.AUTHORIZED
                && status != PaymentIntentStatus.REQUIRES_CAPTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be canceled from status " + status);
        }
        this.status = PaymentIntentStatus.CANCELED;
        this.updatedAt = Instant.now();
    }

    public void refund() {
        refund(amount);
    }

    public void refund(long refundAmount) {
        if (status != PaymentIntentStatus.CAPTURED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be refunded from status " + status);
        }
        if (refundAmount != amount) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Refund amount must equal captured PaymentIntent amount until partial refunds are supported");
        }
        this.status = PaymentIntentStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }

    public void dispute() {
        if (status != PaymentIntentStatus.CAPTURED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot be disputed from status " + status);
        }
        this.status = PaymentIntentStatus.DISPUTED;
        this.updatedAt = Instant.now();
    }

    private void requireConfirmable(String transition) {
        if (status != PaymentIntentStatus.REQUIRES_CONFIRMATION
                && status != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent cannot " + transition + " from status " + status);
        }
    }
}
