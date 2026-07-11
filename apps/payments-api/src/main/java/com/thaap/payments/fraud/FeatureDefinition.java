package com.thaap.payments.fraud;

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
@Table(name = "feature_definitions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_feature_definitions_code_version",
                columnNames = {"feature_code", "feature_version"}),
        @UniqueConstraint(name = "uk_feature_definitions_lineage",
                columnNames = {"id", "feature_code", "feature_version", "value_type"})
})
public class FeatureDefinition {

    @Id
    private UUID id;

    @Column(name = "feature_code", nullable = false, length = 128)
    private String featureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private FeatureEntityType entityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16)
    private FeatureValueType valueType;

    @Column(nullable = false, length = 64)
    private String owner;

    @Column(name = "feature_version", nullable = false, length = 32)
    private String featureVersion;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "max_age_ms")
    private Long maxAgeMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FeatureDefinition() {
    }

    public UUID getId() {
        return id;
    }

    public String getFeatureCode() {
        return featureCode;
    }

    public FeatureEntityType getEntityType() {
        return entityType;
    }

    public FeatureValueType getValueType() {
        return valueType;
    }

    public String getOwner() {
        return owner;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public String getDescription() {
        return description;
    }

    public Long getMaxAgeMs() {
        return maxAgeMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
