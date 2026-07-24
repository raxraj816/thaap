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
@Table(name = "dispute_evidence_packets")
public class DisputeEvidencePacket {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "chargeback_id", nullable = false) private UUID chargebackId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "authorization_id", nullable = false) private UUID authorizationId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "source_attestation", nullable = false, length = 64) private String sourceAttestation;
    @Enumerated(EnumType.STRING) @Column(name = "avs_result", nullable = false, length = 32)
    private DisputeEvidenceResult avsResult;
    @Enumerated(EnumType.STRING) @Column(name = "cvc_result", nullable = false, length = 32)
    private DisputeEvidenceResult cvcResult;
    @Column(name = "three_ds_authenticated", nullable = false) private boolean threeDsAuthenticated;
    @Column(name = "delivery_confirmed", nullable = false) private boolean deliveryConfirmed;
    @Column(name = "customer_communication_present", nullable = false) private boolean customerCommunicationPresent;
    @Column(name = "refund_history_present", nullable = false) private boolean refundHistoryPresent;
    @Column(name = "device_continuity", nullable = false) private boolean deviceContinuity;
    @Column(name = "due_at", nullable = false) private Instant dueAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected DisputeEvidencePacket() {
    }

    public DisputeEvidencePacket(Chargeback chargeback, DisputeEvidenceResult avsResult,
            DisputeEvidenceResult cvcResult, boolean threeDsAuthenticated, boolean deliveryConfirmed,
            boolean customerCommunicationPresent, boolean refundHistoryPresent, boolean deviceContinuity,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = chargeback.getMerchantId();
        this.chargebackId = chargeback.getId();
        this.paymentIntentId = chargeback.getPaymentIntentId();
        this.paymentAttemptId = chargeback.getPaymentAttemptId();
        this.authorizationId = chargeback.getAuthorizationId();
        this.fraudDecisionId = chargeback.getFraudDecisionId();
        this.status = "DRAFT";
        this.sourceAttestation = "SIMULATOR_MERCHANT_INGESTED";
        this.avsResult = avsResult;
        this.cvcResult = cvcResult;
        this.threeDsAuthenticated = threeDsAuthenticated;
        this.deliveryConfirmed = deliveryConfirmed;
        this.customerCommunicationPresent = customerCommunicationPresent;
        this.refundHistoryPresent = refundHistoryPresent;
        this.deviceContinuity = deviceContinuity;
        this.dueAt = chargeback.getEvidenceDueAt();
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getChargebackId() { return chargebackId; }
    public String getStatus() { return status; }
    public String getSourceAttestation() { return sourceAttestation; }
    public DisputeEvidenceResult getAvsResult() { return avsResult; }
    public DisputeEvidenceResult getCvcResult() { return cvcResult; }
    public boolean isThreeDsAuthenticated() { return threeDsAuthenticated; }
    public boolean isDeliveryConfirmed() { return deliveryConfirmed; }
    public boolean isCustomerCommunicationPresent() { return customerCommunicationPresent; }
    public boolean isRefundHistoryPresent() { return refundHistoryPresent; }
    public boolean isDeviceContinuity() { return deviceContinuity; }
    public Instant getDueAt() { return dueAt; }
    public Instant getCreatedAt() { return createdAt; }
}
