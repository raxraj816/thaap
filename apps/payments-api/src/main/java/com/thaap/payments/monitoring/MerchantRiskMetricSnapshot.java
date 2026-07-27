package com.thaap.payments.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_risk_metric_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_merchant_risk_metric_snapshots_merchant_id_id",
                columnNames = {"merchant_id", "id"}),
        indexes = @Index(name = "idx_merchant_risk_metric_snapshots_lookup",
                columnList = "merchant_id,created_at,id"))
public class MerchantRiskMetricSnapshot {

    @Id
    private UUID id;
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;
    @Column(name = "created_by_operator_id", nullable = false)
    private UUID createdByOperatorId;
    @Column(name = "definition_version", nullable = false, length = 32)
    private String definitionVersion;
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;
    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;
    @Column(name = "as_of", nullable = false)
    private Instant asOf;
    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin_policy", nullable = false, length = 32)
    private MonitoringTrafficOriginPolicy trafficOriginPolicy;
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;
    @Column(name = "result_sha256", nullable = false, length = 64)
    private String resultSha256;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantRiskMetricSnapshot() {
    }

    public MerchantRiskMetricSnapshot(UUID merchantId, UUID createdByOperatorId,
            String definitionVersion, Instant windowStart, Instant windowEnd, Instant asOf,
            MonitoringTrafficOriginPolicy trafficOriginPolicy, String resultJson,
            String resultSha256, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.createdByOperatorId = createdByOperatorId;
        this.definitionVersion = definitionVersion;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.asOf = asOf;
        this.trafficOriginPolicy = trafficOriginPolicy;
        this.resultJson = resultJson;
        this.resultSha256 = resultSha256;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCreatedByOperatorId() { return createdByOperatorId; }
    public String getDefinitionVersion() { return definitionVersion; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public Instant getAsOf() { return asOf; }
    public MonitoringTrafficOriginPolicy getTrafficOriginPolicy() { return trafficOriginPolicy; }
    public String getResultJson() { return resultJson; }
    public String getResultSha256() { return resultSha256; }
    public Instant getCreatedAt() { return createdAt; }
}
