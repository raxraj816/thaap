package com.thaap.payments.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_operators", uniqueConstraints = {
        @UniqueConstraint(name = "uk_risk_operators_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_risk_operators_merchant_code", columnNames = {"merchant_id", "operator_code"})
})
public class RiskOperator {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "operator_code", nullable = false, length = 64) private String operatorCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private RiskOperatorRole role;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private RiskOperatorStatus status;
    @Column(name = "lifecycle_epoch", nullable = false) private long lifecycleEpoch;
    @Column(name = "created_by_api_key_prefix", nullable = false, length = 32)
    private String createdByApiKeyPrefix;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "disabled_at") private Instant disabledAt;

    protected RiskOperator() {
    }

    public RiskOperator(UUID merchantId, RiskOperatorRole role, String createdByApiKeyPrefix, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.operatorCode = "rop_" + id.toString().replace("-", "").substring(0, 20);
        this.role = role;
        this.status = RiskOperatorStatus.ACTIVE;
        this.lifecycleEpoch = 1;
        this.createdByApiKeyPrefix = createdByApiKeyPrefix;
        this.createdAt = now;
    }

    public void disable(Instant now) {
        if (status == RiskOperatorStatus.DISABLED) {
            return;
        }
        this.status = RiskOperatorStatus.DISABLED;
        this.lifecycleEpoch = 2;
        this.disabledAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getOperatorCode() { return operatorCode; }
    public RiskOperatorRole getRole() { return role; }
    public RiskOperatorStatus getStatus() { return status; }
    public long getLifecycleEpoch() { return lifecycleEpoch; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public boolean isActive() { return status == RiskOperatorStatus.ACTIVE; }
}
