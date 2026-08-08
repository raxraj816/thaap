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
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import com.thaap.payments.tenant.TrafficOrigin;

@Entity
@Table(name = "feature_snapshots",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_feature_snapshots_merchant_id_id",
                        columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_feature_snapshots_decision",
                        columnNames = {"merchant_id", "fraud_decision_id"}),
                @UniqueConstraint(name = "uk_feature_snapshots_attempt",
                        columnNames = {"merchant_id", "payment_attempt_id"})
        },
        indexes = {
                @Index(name = "idx_feature_snapshots_merchant", columnList = "merchant_id"),
                @Index(name = "idx_feature_snapshots_decision", columnList = "fraud_decision_id"),
                @Index(name = "idx_feature_snapshots_attempt", columnList = "payment_attempt_id"),
                @Index(name = "idx_feature_snapshots_merchant_created", columnList = "merchant_id,created_at,id")
        })
public class FeatureSnapshot {

    public static final String FEATURES_V1 = "features_v1";

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "fraud_decision_id", nullable = false)
    private UUID fraudDecisionId;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(name = "feature_version", nullable = false)
    private String featureVersion;

    @Convert(converter = FeatureMapConverter.class)
    @Column(name = "features_json", nullable = false)
    private Map<String, Object> features;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "oldest_feature_timestamp", nullable = false)
    private Instant oldestFeatureTimestamp;

    @Column(name = "max_feature_age_ms", nullable = false)
    private long maxFeatureAgeMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "freshness_status", nullable = false)
    private FeatureFreshnessStatus freshnessStatus;

    @Convert(converter = StringListConverter.class)
    @Column(name = "stale_feature_names", nullable = false)
    private List<String> staleFeatureNames;

    @Convert(converter = StringListConverter.class)
    @Column(name = "missing_feature_names", nullable = false)
    private List<String> missingFeatureNames;

    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;

    @Column(nullable = false)
    private Instant createdAt;

    protected FeatureSnapshot() {
    }

    public FeatureSnapshot(UUID merchantId, UUID fraudDecisionId, UUID paymentAttemptId, Map<String, Object> features,
            Instant computedAt, Instant oldestFeatureTimestamp, long maxFeatureAgeMs,
            FeatureFreshnessStatus freshnessStatus, List<String> staleFeatureNames, List<String> missingFeatureNames) {
        this(merchantId, fraudDecisionId, paymentAttemptId, features, computedAt, oldestFeatureTimestamp,
                maxFeatureAgeMs, freshnessStatus, staleFeatureNames, missingFeatureNames,
                TrafficOrigin.MERCHANT_API);
    }

    public FeatureSnapshot(UUID merchantId, UUID fraudDecisionId, UUID paymentAttemptId, Map<String, Object> features,
            Instant computedAt, Instant oldestFeatureTimestamp, long maxFeatureAgeMs,
            FeatureFreshnessStatus freshnessStatus, List<String> staleFeatureNames, List<String> missingFeatureNames,
            TrafficOrigin trafficOrigin) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.fraudDecisionId = fraudDecisionId;
        this.paymentAttemptId = paymentAttemptId;
        this.featureVersion = FEATURES_V1;
        this.features = immutableLexicalMap(features);
        this.computedAt = computedAt;
        this.oldestFeatureTimestamp = oldestFeatureTimestamp;
        this.maxFeatureAgeMs = maxFeatureAgeMs;
        this.freshnessStatus = freshnessStatus;
        this.staleFeatureNames = List.copyOf(staleFeatureNames);
        this.missingFeatureNames = List.copyOf(missingFeatureNames);
        this.trafficOrigin = trafficOrigin;
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getFraudDecisionId() {
        return fraudDecisionId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public Map<String, Object> getFeatures() {
        return immutableLexicalMap(features);
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public Instant getOldestFeatureTimestamp() {
        return oldestFeatureTimestamp;
    }

    public long getMaxFeatureAgeMs() {
        return maxFeatureAgeMs;
    }

    public FeatureFreshnessStatus getFreshnessStatus() {
        return freshnessStatus;
    }

    public List<String> getStaleFeatureNames() {
        return List.copyOf(staleFeatureNames);
    }

    public List<String> getMissingFeatureNames() {
        return List.copyOf(missingFeatureNames);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TrafficOrigin getTrafficOrigin() {
        return trafficOrigin;
    }

    private static Map<String, Object> immutableLexicalMap(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(source)));
    }
}
