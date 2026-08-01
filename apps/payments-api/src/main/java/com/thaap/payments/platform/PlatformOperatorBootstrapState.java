package com.thaap.payments.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "platform_operator_bootstrap_state")
public class PlatformOperatorBootstrapState {

    @Id
    @Column(name = "singleton_id")
    private short singletonId;
    @Column(name = "bootstrap_completed", nullable = false)
    private boolean bootstrapCompleted;
    @Column(name = "first_admin_id")
    private UUID firstAdminId;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlatformOperatorBootstrapState() {
    }

    public void complete(UUID adminId, Instant now) {
        if (bootstrapCompleted) {
            throw new IllegalStateException("Platform operator bootstrap is already complete");
        }
        bootstrapCompleted = true;
        firstAdminId = Objects.requireNonNull(adminId, "adminId");
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isBootstrapCompleted() { return bootstrapCompleted; }
    public UUID getFirstAdminId() { return firstAdminId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
