package com.thaap.payments.fraud;

import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "feature_values", uniqueConstraints = {
        @UniqueConstraint(name = "uk_feature_values_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_feature_values_attempt_feature",
                columnNames = {"merchant_id", "payment_attempt_id", "feature_code", "feature_version"})
}, indexes = {
        @Index(name = "idx_feature_values_merchant_attempt", columnList = "merchant_id,payment_attempt_id"),
        @Index(name = "idx_feature_values_point_in_time",
                columnList = "merchant_id,feature_code,feature_version,observed_at,payment_attempt_id")
})
public class FeatureValue {

    @Id
    private UUID id;
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;
    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;
    @Column(name = "feature_definition_id", nullable = false)
    private UUID featureDefinitionId;
    @Column(name = "feature_code", nullable = false, length = 128)
    private String featureCode;
    @Column(name = "feature_version", nullable = false, length = 32)
    private String featureVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16)
    private FeatureValueType valueType;
    @Column(name = "long_value")
    private Long longValue;
    @Column(name = "string_value", length = 256)
    private String stringValue;
    @Column(name = "boolean_value")
    private Boolean booleanValue;
    @Column(name = "decimal_value", precision = 19, scale = 6)
    private BigDecimal decimalValue;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FeatureValue() {
    }

    public FeatureValue(UUID merchantId, UUID paymentAttemptId, FeatureDefinition definition, Object value,
            Instant observedAt, Instant computedAt, Instant decisionStartedAt, TrafficOrigin trafficOrigin) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(computedAt, "computedAt");
        Objects.requireNonNull(decisionStartedAt, "decisionStartedAt");
        if (observedAt.isAfter(decisionStartedAt)) {
            throw new IllegalArgumentException("Feature observed time cannot be after decision start");
        }
        if (observedAt.isAfter(computedAt)) {
            throw new IllegalArgumentException("Feature observed time cannot be after computed time");
        }
        this.id = UUID.randomUUID();
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.paymentAttemptId = Objects.requireNonNull(paymentAttemptId, "paymentAttemptId");
        this.featureDefinitionId = definition.getId();
        this.featureCode = definition.getFeatureCode();
        this.featureVersion = definition.getFeatureVersion();
        this.valueType = definition.getValueType();
        assignTypedValue(value);
        this.observedAt = observedAt;
        this.computedAt = computedAt;
        this.trafficOrigin = Objects.requireNonNull(trafficOrigin, "trafficOrigin");
        this.createdAt = computedAt;
    }

    private void assignTypedValue(Object value) {
        switch (valueType) {
            case LONG -> this.longValue = requireType(value, Long.class);
            case STRING -> {
                String typedValue = requireType(value, String.class);
                if (typedValue.isBlank() || typedValue.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256) {
                    throw new IllegalArgumentException("STRING feature must contain 1 to 256 UTF-8 bytes");
                }
                this.stringValue = typedValue;
            }
            case BOOLEAN -> this.booleanValue = requireType(value, Boolean.class);
            case DECIMAL -> {
                BigDecimal typedValue = requireType(value, BigDecimal.class);
                int integerDigits = typedValue.precision() - typedValue.scale();
                if (typedValue.scale() < 0 || typedValue.scale() > 6 || integerDigits > 13
                        || typedValue.precision() > 19) {
                    throw new IllegalArgumentException("DECIMAL feature exceeds NUMERIC(19,6) exact range");
                }
                this.decimalValue = typedValue;
            }
        }
    }

    private static <T> T requireType(Object value, Class<T> expectedType) {
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Feature value does not match definition type "
                    + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    public Object typedValue() {
        return switch (valueType) {
            case LONG -> longValue;
            case STRING -> stringValue;
            case BOOLEAN -> booleanValue;
            case DECIMAL -> decimalValue;
        };
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

    public UUID getFeatureDefinitionId() {
        return featureDefinitionId;
    }

    public String getFeatureCode() {
        return featureCode;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public FeatureValueType getValueType() {
        return valueType;
    }

    public Long getLongValue() {
        return longValue;
    }

    public String getStringValue() {
        return stringValue;
    }

    public Boolean getBooleanValue() {
        return booleanValue;
    }

    public BigDecimal getDecimalValue() {
        return decimalValue;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public TrafficOrigin getTrafficOrigin() {
        return trafficOrigin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
