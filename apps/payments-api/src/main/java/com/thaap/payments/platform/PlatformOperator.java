package com.thaap.payments.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "platform_operators")
public class PlatformOperator {

    @Id
    private UUID id;
    @Column(name = "operator_code", nullable = false, length = 32)
    private String operatorCode;
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PlatformOperatorRole role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlatformOperatorStatus status;
    @Column(name = "lifecycle_epoch", nullable = false)
    private long lifecycleEpoch;
    @Column(name = "created_by_operator_id")
    private UUID createdByOperatorId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "disabled_at")
    private Instant disabledAt;

    protected PlatformOperator() {
    }

    public PlatformOperator(String displayName, PlatformOperatorRole role,
            UUID createdByOperatorId, Instant now) {
        this.id = UUID.randomUUID();
        this.operatorCode = "pop_" + id.toString().replace("-", "").substring(0, 20);
        this.displayName = normalizeDisplayName(displayName);
        this.role = Objects.requireNonNull(role, "role");
        this.status = PlatformOperatorStatus.ACTIVE;
        this.lifecycleEpoch = 1;
        this.createdByOperatorId = createdByOperatorId;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public void disable(Instant now) {
        if (status == PlatformOperatorStatus.DISABLED) {
            return;
        }
        status = PlatformOperatorStatus.DISABLED;
        lifecycleEpoch++;
        disabledAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() { return id; }
    public String getOperatorCode() { return operatorCode; }
    public String getDisplayName() { return displayName; }
    public PlatformOperatorRole getRole() { return role; }
    public PlatformOperatorStatus getStatus() { return status; }
    public long getLifecycleEpoch() { return lifecycleEpoch; }
    public UUID getCreatedByOperatorId() { return createdByOperatorId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public boolean isActive() { return status == PlatformOperatorStatus.ACTIVE; }

    private static String normalizeDisplayName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Platform operator display name is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException(
                    "Platform operator display name must contain at most 120 characters");
        }
        return normalized;
    }
}
