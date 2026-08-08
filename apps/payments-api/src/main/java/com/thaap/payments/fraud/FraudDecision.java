package com.thaap.payments.fraud;

import com.thaap.payments.time.DatabaseInstant;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.thaap.payments.tenant.TrafficOrigin;

@Entity
@Table(name = "fraud_decisions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_fraud_decisions_merchant_id_id", columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_fraud_decisions_attempt",
                        columnNames = {"merchant_id", "payment_attempt_id"})
        },
        indexes = {
                @Index(name = "idx_fraud_decisions_merchant", columnList = "merchant_id"),
                @Index(name = "idx_fraud_decisions_merchant_created", columnList = "merchant_id,created_at,id"),
                @Index(name = "idx_fraud_decisions_attempt", columnList = "payment_attempt_id"),
                @Index(name = "idx_fraud_decisions_merchant_attempt", columnList = "merchant_id,payment_attempt_id")
        })
public class FraudDecision {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_attempt_id")
    private UUID paymentAttemptId;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private FraudRiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FraudDecisionAction action;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "ruleset_version", nullable = false)
    private String rulesetVersion;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "decision_started_at", nullable = false)
    private Instant decisionStartedAt;

    @Column(name = "decision_completed_at", nullable = false)
    private Instant decisionCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature_freshness_status", nullable = false)
    private FeatureFreshnessStatus featureFreshnessStatus;

    @Column(name = "max_feature_age_ms", nullable = false)
    private long maxFeatureAgeMs;

    @Convert(converter = FraudDecisionReasonCodeListConverter.class)
    @Column(name = "reason_codes", nullable = false)
    private List<FraudDecisionReasonCode> reasonCodes;

    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;

    @Column(nullable = false)
    private Instant createdAt;

    protected FraudDecision() {
    }

    public FraudDecision(UUID merchantId, UUID paymentAttemptId, ConfirmDecision decision, long latencyMs,
            Instant decisionStartedAt, Instant decisionCompletedAt, FeatureFreshnessStatus featureFreshnessStatus,
            long maxFeatureAgeMs) {
        this(merchantId, paymentAttemptId, decision, latencyMs, decisionStartedAt, decisionCompletedAt,
                featureFreshnessStatus, maxFeatureAgeMs, TrafficOrigin.MERCHANT_API);
    }

    public FraudDecision(UUID merchantId, UUID paymentAttemptId, ConfirmDecision decision, long latencyMs,
            Instant decisionStartedAt, Instant decisionCompletedAt, FeatureFreshnessStatus featureFreshnessStatus,
            long maxFeatureAgeMs, TrafficOrigin trafficOrigin) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentAttemptId = paymentAttemptId;
        this.riskScore = decision.riskScore();
        this.riskLevel = decision.riskLevel();
        this.action = decision.action();
        this.modelVersion = decision.modelVersion();
        this.rulesetVersion = decision.rulesetVersion();
        this.latencyMs = latencyMs;
        this.decisionStartedAt = decisionStartedAt;
        this.decisionCompletedAt = decisionCompletedAt;
        this.featureFreshnessStatus = featureFreshnessStatus;
        this.maxFeatureAgeMs = maxFeatureAgeMs;
        this.reasonCodes = List.copyOf(decision.reasonCodes());
        this.trafficOrigin = trafficOrigin;
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public FraudRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public FraudDecisionAction getAction() {
        return action;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getDecisionStartedAt() {
        return decisionStartedAt;
    }

    public Instant getDecisionCompletedAt() {
        return decisionCompletedAt;
    }

    public FeatureFreshnessStatus getFeatureFreshnessStatus() {
        return featureFreshnessStatus;
    }

    public long getMaxFeatureAgeMs() {
        return maxFeatureAgeMs;
    }

    public List<FraudDecisionReasonCode> getReasonCodes() {
        return List.copyOf(reasonCodes);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TrafficOrigin getTrafficOrigin() {
        return trafficOrigin;
    }
}
