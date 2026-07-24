package com.thaap.payments.review;

import com.thaap.payments.payment.Refund;
import com.thaap.payments.payment.RefundReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_refund_actions")
public class ReviewRefundAction {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "review_resolution_id", nullable = false) private UUID reviewResolutionId;
    @Column(name = "refund_id", nullable = false) private UUID refundId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "action_result", nullable = false, length = 16) private String actionResult;
    @Column(name = "reason_catalog_version", nullable = false) private int reasonCatalogVersion;
    @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 64)
    private RefundReasonCode reasonCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ReviewRefundAction() {}

    public ReviewRefundAction(ReviewResolution resolution, Refund refund,
            RefundReasonCode actionReason, boolean reused, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = resolution.getMerchantId();
        this.reviewResolutionId = resolution.getId();
        this.refundId = refund.getId();
        this.paymentIntentId = resolution.getPaymentIntentId();
        this.actionResult = reused ? "REUSED" : "CREATED";
        this.reasonCatalogVersion = 1;
        this.reasonCode = actionReason;
        this.createdAt = now;
    }

    public UUID getRefundId() { return refundId; }
    public String getActionResult() { return actionResult; }
    public RefundReasonCode getReasonCode() { return reasonCode; }
}
