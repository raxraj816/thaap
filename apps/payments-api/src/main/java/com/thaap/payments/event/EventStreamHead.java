package com.thaap.payments.event;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "event_stream_heads")
public class EventStreamHead {

    @EmbeddedId
    private EventStreamHeadId id;

    @Column(name = "current_version", nullable = false)
    private long currentVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventStreamHead() {
    }

    public long allocateNextVersion(Instant now) {
        if (currentVersion >= CanonicalEventCodec.MAX_I_JSON_SAFE_INTEGER) {
            throw new IllegalStateException("Event stream exhausted the safe canonical version range");
        }
        currentVersion++;
        updatedAt = EventTimePrecision.toPostgreSqlMicroseconds(now);
        return currentVersion;
    }

    public EventStreamHeadId getId() {
        return id;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
