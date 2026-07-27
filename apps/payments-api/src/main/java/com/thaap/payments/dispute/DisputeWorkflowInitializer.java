package com.thaap.payments.dispute;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DisputeWorkflowInitializer {

    public static final String DEADLINE_CATALOGUE_VERSION = "dispute_deadlines_v1";

    private final JdbcTemplate jdbcTemplate;

    public DisputeWorkflowInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID initialize(Chargeback chargeback, Instant now) {
        Integer responseDays = jdbcTemplate.queryForObject("""
                SELECT response_days FROM dispute_reason_deadline_definitions WHERE reason_code = ?
                """, Integer.class, chargeback.getReasonCode().name());
        if (responseDays == null) {
            throw new IllegalStateException("Dispute deadline definition is unavailable");
        }
        UUID workflowId = UUID.randomUUID();
        Instant canonicalNow = now.truncatedTo(ChronoUnit.MICROS);
        Instant dueAt = chargeback.getReceivedAt().plus(responseDays, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("""
                INSERT INTO dispute_workflows
                    (id, merchant_id, chargeback_id, reason_code, deadline_catalogue_version,
                     due_at, lifecycle_status, readiness_status, missing_requirements,
                     projection_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 'INCOMPLETE', 'NO_EVIDENCE_VERSION', 0, ?, ?)
                """, workflowId, chargeback.getMerchantId(), chargeback.getId(),
                chargeback.getReasonCode().name(), DEADLINE_CATALOGUE_VERSION,
                Timestamp.from(dueAt), Timestamp.from(canonicalNow), Timestamp.from(canonicalNow));
        insertSystemEvent(chargeback.getMerchantId(), workflowId, "WORKFLOW_CREATED", canonicalNow);
        return workflowId;
    }

    private void insertSystemEvent(UUID merchantId, UUID workflowId, String type, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO dispute_workflow_events
                    (id, merchant_id, dispute_workflow_id, event_type, source, occurred_at)
                VALUES (?, ?, ?, ?, 'SYSTEM', ?)
                """, UUID.randomUUID(), merchantId, workflowId, type, Timestamp.from(now));
    }
}
