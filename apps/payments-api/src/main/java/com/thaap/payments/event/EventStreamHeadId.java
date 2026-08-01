package com.thaap.payments.event;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class EventStreamHeadId implements Serializable {

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stream_type", nullable = false, length = 32)
    private DomainStreamType streamType;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    protected EventStreamHeadId() {
    }

    public EventStreamHeadId(UUID merchantId, DomainStreamType streamType, UUID streamId) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.streamType = Objects.requireNonNull(streamType, "streamType");
        this.streamId = Objects.requireNonNull(streamId, "streamId");
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public DomainStreamType getStreamType() {
        return streamType;
    }

    public UUID getStreamId() {
        return streamId;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof EventStreamHeadId other)) {
            return false;
        }
        return merchantId.equals(other.merchantId)
                && streamType == other.streamType
                && streamId.equals(other.streamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(merchantId, streamType, streamId);
    }
}
