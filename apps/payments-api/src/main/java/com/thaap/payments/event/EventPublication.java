package com.thaap.payments.event;

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
@Table(name = "event_publications", uniqueConstraints = {
        @UniqueConstraint(name = "uk_event_publications_merchant_id_id",
                columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_event_publications_event",
                columnNames = {"merchant_id", "domain_event_id"})
})
public class EventPublication {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "domain_event_id", nullable = false) private UUID domainEventId;
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 24)
    private EventPublicationStatus lifecycleStatus;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected EventPublication() {
    }

    public EventPublication(UUID merchantId, UUID domainEventId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.domainEventId = domainEventId;
        this.lifecycleStatus = EventPublicationStatus.PENDING;
        Instant normalizedCreatedAt = EventTimePrecision.toPostgreSqlMicroseconds(createdAt);
        this.createdAt = normalizedCreatedAt;
        this.nextAttemptAt = normalizedCreatedAt;
        this.updatedAt = normalizedCreatedAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getDomainEventId() { return domainEventId; }
    public EventPublicationStatus getLifecycleStatus() { return lifecycleStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
