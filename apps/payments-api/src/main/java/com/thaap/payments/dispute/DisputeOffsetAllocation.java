package com.thaap.payments.dispute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispute_offset_allocations")
public class DisputeOffsetAllocation {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "chargeback_id", nullable = false) private UUID chargebackId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "refund_id") private UUID refundId;
    @Column(name = "captured_amount", nullable = false) private long capturedAmount;
    @Column(name = "prior_refund_amount", nullable = false) private long priorRefundAmount;
    @Column(name = "prior_dispute_amount", nullable = false) private long priorDisputeAmount;
    @Column(name = "allocated_amount", nullable = false) private long allocatedAmount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ChargebackStatus result;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected DisputeOffsetAllocation() {
    }

    public DisputeOffsetAllocation(Chargeback chargeback, UUID refundId, long capturedAmount,
            long priorRefundAmount, long priorDisputeAmount, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = chargeback.getMerchantId();
        this.chargebackId = chargeback.getId();
        this.paymentIntentId = chargeback.getPaymentIntentId();
        this.refundId = refundId;
        this.capturedAmount = capturedAmount;
        this.priorRefundAmount = priorRefundAmount;
        this.priorDisputeAmount = priorDisputeAmount;
        this.allocatedAmount = chargeback.getAllocatedAmount();
        this.currency = chargeback.getCurrency();
        this.result = chargeback.getStatus();
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getChargebackId() { return chargebackId; }
    public UUID getRefundId() { return refundId; }
    public long getAllocatedAmount() { return allocatedAmount; }
    public ChargebackStatus getResult() { return result; }
}
