package com.thaap.payments.monitoring;

import com.thaap.payments.riskpolicy.MerchantRiskProfileReasonCode;
import com.thaap.payments.riskpolicy.MerchantRiskTier;
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
@Table(name = "merchant_risk_profile_recommendations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_merchant_risk_profile_recommendations_merchant_id_id",
                        columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_merchant_risk_profile_recommendations_snapshot",
                        columnNames = {"merchant_id", "metric_snapshot_id"})
        })
public class MerchantRiskProfileRecommendation {

    @Id
    private UUID id;
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;
    @Column(name = "metric_snapshot_id", nullable = false)
    private UUID metricSnapshotId;
    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_tier", nullable = false, length = 16)
    private MerchantRiskTier recommendedTier;
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_metric_code", nullable = false, length = 64)
    private MerchantRiskMetricCode reasonMetricCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 64)
    private MerchantRiskProfileReasonCode reasonCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantRiskProfileRecommendation() {
    }

    public MerchantRiskProfileRecommendation(UUID merchantId, UUID metricSnapshotId,
            MerchantRiskTier recommendedTier, MerchantRiskMetricCode reasonMetricCode,
            MerchantRiskProfileReasonCode reasonCode, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.metricSnapshotId = metricSnapshotId;
        this.recommendedTier = recommendedTier;
        this.reasonMetricCode = reasonMetricCode;
        this.reasonCode = reasonCode;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getMetricSnapshotId() { return metricSnapshotId; }
    public MerchantRiskTier getRecommendedTier() { return recommendedTier; }
    public MerchantRiskMetricCode getReasonMetricCode() { return reasonMetricCode; }
    public MerchantRiskProfileReasonCode getReasonCode() { return reasonCode; }
    public Instant getCreatedAt() { return createdAt; }
}
