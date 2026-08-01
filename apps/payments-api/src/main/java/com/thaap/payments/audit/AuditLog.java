package com.thaap.payments.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_merchant", columnList = "merchant_id"),
        @Index(name = "idx_audit_logs_merchant_created", columnList = "merchant_id, created_at, id"),
        @Index(name = "idx_audit_logs_merchant_action_created", columnList = "merchant_id, action, created_at, id"),
        @Index(name = "idx_audit_logs_merchant_aggregate", columnList = "merchant_id, aggregate_type, aggregate_id, created_at, id"),
        @Index(name = "idx_audit_logs_request_id", columnList = "request_id")
})
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 64)
    private AuditActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(length = 500)
    private String reason;

    @Convert(converter = AuditPayloadConverter.class)
    @Column(name = "before_json", columnDefinition = "TEXT")
    private Map<String, Object> before;

    @Convert(converter = AuditPayloadConverter.class)
    @Column(name = "after_json", columnDefinition = "TEXT")
    private Map<String, Object> after;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(UUID merchantId, AuditActorType actorType, String actorId, String action, String aggregateType,
            UUID aggregateId, String requestId, String traceId, String reason, Map<String, Object> before,
            Map<String, Object> after) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.requestId = requestId;
        this.traceId = traceId;
        this.reason = reason;
        this.before = before == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(before));
        this.after = after == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
