package com.thaap.payments.payment;

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

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    public PaymentIntent(UUID merchantId, UUID customerId, long amount, String currency) {
        this(merchantId, customerId, amount, currency, CaptureMethod.AUTOMATIC);
    }

    public PaymentIntent(UUID merchantId, UUID customerId, long amount, String currency, CaptureMethod captureMethod) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency.toUpperCase();
        this.captureMethod = captureMethod;
        this.status = PaymentIntentStatus.REQUIRES_CONFIRMATION;
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

    public void capture() {
        this.status = PaymentIntentStatus.CAPTURED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = PaymentIntentStatus.CANCELED;
        this.updatedAt = Instant.now();
    }

    public void refund() {
        this.status = PaymentIntentStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }
}
