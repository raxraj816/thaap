package com.thaap.payments.velocity;

import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "velocity_observations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_velocity_observations_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_velocity_observations_attempt_dimension",
                columnNames = {"merchant_id", "payment_attempt_id", "dimension"})
}, indexes = {
        @Index(name = "idx_velocity_observations_merchant_attempt",
                columnList = "merchant_id,payment_attempt_id,dimension"),
        @Index(name = "idx_velocity_observations_decision",
                columnList = "merchant_id,fraud_decision_id,dimension")
})
public class VelocityObservationRecord {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id") private UUID fraudDecisionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private VelocityDimension dimension;
    @Column(name = "window_ms", nullable = false) private long windowMs;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private VelocityAvailability availability;
    @Column(name = "other_attempt_count") private Long otherAttemptCount;
    @Column(name = "redis_time_ms") private Long redisTimeMs;
    @Column(name = "observed_at") private Instant observedAt;
    @Column(name = "script_version", nullable = false, length = 32) private String scriptVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 16) private VelocityObservation.ExecutionMode executionMode;
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_cause", length = 32) private VelocityUnavailableException.Cause failureCause;
    @Column(name = "identity_key_version", nullable = false, length = 32) private String identityKeyVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32) private TrafficOrigin trafficOrigin;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected VelocityObservationRecord() {
    }

    public VelocityObservationRecord(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            VelocitySignal signal, String identityKeyVersion, TrafficOrigin trafficOrigin, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.paymentIntentId = Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        this.paymentAttemptId = Objects.requireNonNull(paymentAttemptId, "paymentAttemptId");
        this.dimension = signal.dimension();
        this.windowMs = signal.dimension().window().toMillis();
        this.availability = signal.availability();
        this.otherAttemptCount = signal.otherAttemptCount();
        this.redisTimeMs = signal.observedAt() == null ? null : signal.observedAt().toEpochMilli();
        this.observedAt = signal.observedAt();
        this.scriptVersion = signal.scriptVersion();
        this.executionMode = signal.executionMode();
        this.failureCause = signal.failureCause();
        this.identityKeyVersion = Objects.requireNonNull(identityKeyVersion, "identityKeyVersion");
        this.trafficOrigin = Objects.requireNonNull(trafficOrigin, "trafficOrigin");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public void attachFraudDecision(UUID fraudDecisionId) {
        if (this.fraudDecisionId != null && !this.fraudDecisionId.equals(fraudDecisionId)) {
            throw new IllegalStateException("Velocity decision lineage is immutable");
        }
        this.fraudDecisionId = Objects.requireNonNull(fraudDecisionId, "fraudDecisionId");
    }

    public UUID getId() { return id; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public VelocityDimension getDimension() { return dimension; }
    public VelocityAvailability getAvailability() { return availability; }
    public Long getOtherAttemptCount() { return otherAttemptCount; }
    public Instant getObservedAt() { return observedAt; }
    public String getScriptVersion() { return scriptVersion; }
    public VelocityUnavailableException.Cause getFailureCause() { return failureCause; }
}
