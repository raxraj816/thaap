package com.thaap.payments.fraud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_decisions", indexes = @Index(name = "idx_fraud_decisions_merchant", columnList = "merchant_id"))
public class FraudDecision {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private Instant createdAt;

    protected FraudDecision() {
    }

    public FraudDecision(UUID merchantId, String action) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.action = action;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getAction() {
        return action;
    }
}
