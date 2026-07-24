package com.thaap.payments.dispute;

import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chargebacks")
public class Chargeback {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "external_event_key", nullable = false, length = 128) private String externalEventKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64) private String requestFingerprint;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "authorization_id", nullable = false) private UUID authorizationId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Column(nullable = false) private long amount;
    @Column(name = "allocated_amount", nullable = false) private long allocatedAmount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 64)
    private ChargebackReasonCode reasonCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ChargebackStatus status;
    @Column(name = "source_attestation", nullable = false, length = 64) private String sourceAttestation;
    @Enumerated(EnumType.STRING) @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;
    @Column(name = "evidence_occurred_at", nullable = false) private Instant evidenceOccurredAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "evidence_due_at", nullable = false) private Instant evidenceDueAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected Chargeback() {
    }

    public Chargeback(UUID merchantId, String externalEventKey, String requestFingerprint,
            UUID paymentIntentId, UUID paymentAttemptId, UUID authorizationId, UUID fraudDecisionId,
            long amount, long allocatedAmount, String currency, ChargebackReasonCode reasonCode,
            TrafficOrigin trafficOrigin, Instant evidenceOccurredAt, Instant receivedAt,
            Instant availableAt, Instant evidenceDueAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.externalEventKey = externalEventKey;
        this.requestFingerprint = requestFingerprint;
        this.paymentIntentId = paymentIntentId;
        this.paymentAttemptId = paymentAttemptId;
        this.authorizationId = authorizationId;
        this.fraudDecisionId = fraudDecisionId;
        this.amount = amount;
        this.allocatedAmount = allocatedAmount;
        this.currency = currency.toUpperCase();
        this.reasonCode = reasonCode;
        this.status = allocatedAmount == 0 ? ChargebackStatus.OFFSET_BY_REFUND : ChargebackStatus.ALLOCATED;
        this.sourceAttestation = "SIMULATOR_MERCHANT_INGESTED";
        this.trafficOrigin = trafficOrigin;
        this.evidenceOccurredAt = evidenceOccurredAt;
        this.receivedAt = receivedAt;
        this.availableAt = availableAt;
        this.evidenceDueAt = evidenceDueAt;
        this.createdAt = availableAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getExternalEventKey() { return externalEventKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getAuthorizationId() { return authorizationId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public long getAmount() { return amount; }
    public long getAllocatedAmount() { return allocatedAmount; }
    public String getCurrency() { return currency; }
    public ChargebackReasonCode getReasonCode() { return reasonCode; }
    public ChargebackStatus getStatus() { return status; }
    public String getSourceAttestation() { return sourceAttestation; }
    public TrafficOrigin getTrafficOrigin() { return trafficOrigin; }
    public Instant getEvidenceOccurredAt() { return evidenceOccurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getEvidenceDueAt() { return evidenceDueAt; }
    public Instant getCreatedAt() { return createdAt; }
}
