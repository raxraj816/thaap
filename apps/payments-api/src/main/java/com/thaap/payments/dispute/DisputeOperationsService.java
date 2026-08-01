package com.thaap.payments.dispute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorCredential;
import com.thaap.payments.risk.RiskOperatorCredentialRepository;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DisputeOperationsService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final DisputeOperationsMetrics metrics;
    private final RiskOperatorRepository operatorRepository;
    private final RiskOperatorCredentialRepository credentialRepository;
    private final RiskOperationsEventProducer eventProducer;
    private final Clock clock;

    public DisputeOperationsService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            AuditLogService auditLogService, DisputeOperationsMetrics metrics,
            RiskOperatorRepository operatorRepository,
            RiskOperatorCredentialRepository credentialRepository,
            RiskOperationsEventProducer eventProducer, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.eventProducer = eventProducer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(RiskOperatorContext context, DisputeWorkflowStatus status, int limit) {
        requireReader(context);
        if (limit < 1 || limit > 100) {
            throw badRequest("limit must be between 1 and 100");
        }
        String statusFilter = status == null ? "" : " AND workflow.lifecycle_status = ?";
        Object[] arguments = status == null
                ? new Object[] {context.merchantId(), limit}
                : new Object[] {context.merchantId(), status.name(), limit};
        return jdbcTemplate.query(workflowSelect() + " WHERE workflow.merchant_id = ?"
                        + statusFilter + " ORDER BY workflow.due_at, workflow.id LIMIT ?",
                (resultSet, rowNumber) -> mapWorkflow(resultSet), arguments);
    }

    @Transactional(readOnly = true)
    public WorkflowView get(RiskOperatorContext context, UUID chargebackId) {
        requireReader(context);
        return requireWorkflow(context.merchantId(), chargebackId, false);
    }

    @Transactional
    public WorkflowView reviseEvidence(RiskOperatorContext context, UUID chargebackId,
            boolean deliveryConfirmed, boolean customerCommunicationPresent,
            boolean deviceContinuity, String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        requireManager(actor);
        WorkflowView workflow = requireWorkflow(context.merchantId(), chargebackId, true);
        Instant now = now();
        if (workflow.lifecycleStatus() != DisputeWorkflowStatus.DRAFT
                && workflow.lifecycleStatus() != DisputeWorkflowStatus.READY) {
            throw conflict("Only an open dispute workflow accepts evidence revisions");
        }
        if (isPastDeadline(now, workflow.dueAt())) {
            throw conflict("Dispute evidence deadline has passed");
        }
        EvidenceFacts facts = evidenceFacts(context.merchantId(), chargebackId,
                deliveryConfirmed, customerCommunicationPresent, deviceContinuity);
        Requirements requirements = requirements(workflow.reasonCode(), facts);
        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1 FROM dispute_evidence_versions
                 WHERE merchant_id = ? AND dispute_workflow_id = ?
                """, Integer.class, context.merchantId(), workflow.id());
        int version = nextVersion == null ? 1 : nextVersion;
        UUID evidenceId = UUID.randomUUID();
        String canonical = canonicalEvidence(version, facts);
        String digest = SecretHasher.sha256(canonical);
        jdbcTemplate.update("""
                INSERT INTO dispute_evidence_versions
                    (id, merchant_id, dispute_workflow_id, version_number, avs_result, cvc_result,
                     three_ds_authenticated, delivery_confirmed, customer_communication_present,
                     refund_history_present, device_continuity, canonical_evidence_json,
                     evidence_digest, created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, evidenceId, context.merchantId(), workflow.id(), version,
                facts.avsResult(), facts.cvcResult(), facts.threeDsAuthenticated(),
                facts.deliveryConfirmed(), facts.customerCommunicationPresent(),
                facts.refundHistoryPresent(), facts.deviceContinuity(), canonical, digest,
                context.operatorId(), Timestamp.from(now));
        String nextStatus = requirements.missing().isEmpty() ? "READY" : "DRAFT";
        jdbcTemplate.update("""
                UPDATE dispute_workflows
                   SET lifecycle_status = ?, readiness_status = ?, missing_requirements = ?,
                       latest_evidence_version_id = ?, projection_version = projection_version + 1,
                       updated_at = ?
                 WHERE merchant_id = ? AND id = ?
                """, nextStatus, requirements.missing().isEmpty() ? "READY" : "INCOMPLETE",
                String.join(",", requirements.missing()), evidenceId, Timestamp.from(now),
                context.merchantId(), workflow.id());
        insertOperatorEvent(context, workflow.id(), "EVIDENCE_REVISED", evidenceId, now);
        auditLogService.recordRiskOperatorAction(context, requestId, "dispute.evidence_revised",
                "dispute_workflow", workflow.id(), "Immutable dispute evidence version appended",
                Map.of("chargebackId", chargebackId.toString(), "priorStatus", workflow.lifecycleStatus().name()),
                Map.of("evidenceVersion", version, "readiness", nextStatus,
                        "missingRequirements", requirements.missing()));
        metrics.record("EVIDENCE_REVISED", nextStatus);
        return requireWorkflow(context.merchantId(), chargebackId, false);
    }

    @Transactional
    public WorkflowView submit(RiskOperatorContext context, UUID chargebackId,
            int expectedEvidenceVersion, String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        requireManager(actor);
        WorkflowView workflow = requireWorkflow(context.merchantId(), chargebackId, true);
        Instant now = now();
        if (isPastDeadline(now, workflow.dueAt())) {
            throw conflict("Dispute evidence deadline has passed");
        }
        if (workflow.lifecycleStatus() != DisputeWorkflowStatus.READY
                || workflow.latestEvidenceVersionId() == null || !workflow.missingRequirements().isEmpty()) {
            throw conflict("Latest dispute evidence is not ready for submission");
        }
        Integer actualVersion = jdbcTemplate.queryForObject("""
                SELECT version_number FROM dispute_evidence_versions
                 WHERE merchant_id = ? AND id = ?
                """, Integer.class, context.merchantId(), workflow.latestEvidenceVersionId());
        if (actualVersion == null || actualVersion != expectedEvidenceVersion) {
            throw conflict("Latest dispute evidence version changed");
        }
        jdbcTemplate.update("""
                UPDATE dispute_workflows SET lifecycle_status = 'SUBMITTED',
                       submitted_evidence_version_id = latest_evidence_version_id,
                       submitted_at = ?, updated_at = ?, projection_version = projection_version + 1
                 WHERE merchant_id = ? AND id = ?
                """, Timestamp.from(now), Timestamp.from(now), context.merchantId(), workflow.id());
        insertOperatorEvent(context, workflow.id(), "SUBMITTED", workflow.latestEvidenceVersionId(), now);
        eventProducer.disputeEvidenceSubmitted(context, workflow.id(), now, requestId,
                actualVersion, workflow.latestEvidenceDigest());
        auditLogService.recordRiskOperatorAction(context, requestId, "dispute.submitted",
                "dispute_workflow", workflow.id(), "Simulator dispute packet submitted",
                Map.of("chargebackId", chargebackId.toString(), "status", "READY"),
                Map.of("status", "SUBMITTED", "evidenceVersion", actualVersion));
        metrics.record("SUBMITTED", "SUCCEEDED");
        return requireWorkflow(context.merchantId(), chargebackId, false);
    }

    @Transactional
    public OutcomeView recordOutcome(MerchantContext context, UUID chargebackId,
            String externalEventKey, DisputeOutcome outcome, Instant occurredAt, String requestId) {
        if (context.trafficOrigin() != TrafficOrigin.SYNTHETIC_SCENARIO) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Trusted scenario runner credential is required");
        }
        Instant receivedAt = now();
        if (occurredAt == null || occurredAt.isAfter(receivedAt)) {
            throw badRequest("Outcome occurredAt must not be in the future");
        }
        String requestDigest = SecretHasher.sha256(chargebackId + "|" + outcome.name() + "|" + occurredAt);
        OutcomeView duplicate = findOutcomeByEvent(context.merchantId(), externalEventKey);
        if (duplicate != null) {
            if (!duplicate.requestDigest().equals(requestDigest)) {
                throw conflict("Dispute outcome event key was reused with different content");
            }
            metrics.record("OUTCOME", "DEDUPLICATED");
            return duplicate;
        }
        WorkflowView workflow = requireWorkflow(context.merchantId(), chargebackId, true);
        if (workflow.lifecycleStatus() != DisputeWorkflowStatus.SUBMITTED
                || workflow.submittedEvidenceVersionId() == null) {
            throw conflict("Dispute outcome requires a submitted evidence version");
        }
        if (workflow.submittedAt() != null && occurredAt.isBefore(workflow.submittedAt())) {
            throw conflict("Dispute outcome occurred before evidence submission");
        }
        UUID outcomeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dispute_outcomes
                    (id, merchant_id, dispute_workflow_id, submitted_evidence_version_id,
                     external_event_key, request_digest, outcome, source_attestation,
                     occurred_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRUSTED_SIMULATOR_OUTCOME', ?, ?)
                """, outcomeId, context.merchantId(), workflow.id(),
                workflow.submittedEvidenceVersionId(), externalEventKey, requestDigest,
                outcome.name(), Timestamp.from(occurredAt), Timestamp.from(receivedAt));
        jdbcTemplate.update("""
                UPDATE dispute_workflows SET lifecycle_status = ?, terminal_outcome_id = ?,
                       resolved_at = ?, updated_at = ?, projection_version = projection_version + 1
                 WHERE merchant_id = ? AND id = ?
                """, outcome.name(), outcomeId, Timestamp.from(receivedAt), Timestamp.from(receivedAt),
                context.merchantId(), workflow.id());
        insertSystemOutcomeEvent(context.merchantId(), workflow.id(), outcomeId, outcome, receivedAt);
        eventProducer.disputeOutcomeRecorded(context, workflow.id(), occurredAt, requestId,
                outcome.name(), null, null, context.trafficOrigin());
        auditLogService.recordMerchantApiAction(context, requestId, "dispute.outcome_recorded",
                "dispute_workflow", workflow.id(), "Trusted simulator dispute outcome recorded",
                Map.of("chargebackId", chargebackId.toString(), "status", "SUBMITTED"),
                Map.of("status", outcome.name(), "outcomeId", outcomeId.toString(),
                        "sourceAttestation", "TRUSTED_SIMULATOR_OUTCOME"));
        metrics.record("OUTCOME", outcome.name());
        return requireOutcome(context.merchantId(), outcomeId);
    }

    @Transactional
    public int expireDue(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Dispute expiry batch size must be between 1 and 100");
        }
        Instant now = now();
        List<UUID> dueIds = jdbcTemplate.queryForList("""
                SELECT id FROM dispute_workflows
                 WHERE lifecycle_status IN ('DRAFT','READY') AND due_at < ?
                 ORDER BY due_at, id LIMIT ? FOR UPDATE
                """, UUID.class, Timestamp.from(now), limit);
        List<WorkflowView> due = dueIds.stream().map(this::requireWorkflowById).toList();
        for (WorkflowView workflow : due) {
            expireLocked(workflow, now);
        }
        if (!due.isEmpty()) {
            metrics.record("EXPIRY_SWEEP", "EXPIRED");
        }
        return due.size();
    }

    private void expireLocked(WorkflowView workflow, Instant now) {
        jdbcTemplate.update("""
                UPDATE dispute_workflows SET lifecycle_status = 'EXPIRED', expired_at = ?,
                       updated_at = ?, projection_version = projection_version + 1
                 WHERE merchant_id = ? AND id = ? AND lifecycle_status IN ('DRAFT','READY')
                """, Timestamp.from(now), Timestamp.from(now), workflow.merchantId(), workflow.id());
        jdbcTemplate.update("""
                INSERT INTO dispute_workflow_events
                    (id, merchant_id, dispute_workflow_id, event_type, source, occurred_at)
                VALUES (?, ?, ?, 'EXPIRED', 'SYSTEM', ?)
                """, UUID.randomUUID(), workflow.merchantId(), workflow.id(), Timestamp.from(now));
        eventProducer.disputeWorkflowExpired(workflow.merchantId(), workflow.id(), now);
    }

    static boolean isPastDeadline(Instant now, Instant dueAt) {
        return now.isAfter(dueAt);
    }

    private EvidenceFacts evidenceFacts(UUID merchantId, UUID chargebackId,
            boolean delivery, boolean communication, boolean device) {
        EvidenceFacts facts = jdbcTemplate.query("""
                SELECT CASE auth.avs_postal_code_check
                           WHEN 'PASS' THEN 'MATCH' WHEN 'FAIL' THEN 'NO_MATCH' ELSE 'UNAVAILABLE' END,
                       CASE auth.cvc_check
                           WHEN 'PASS' THEN 'MATCH' WHEN 'FAIL' THEN 'NO_MATCH' ELSE 'UNAVAILABLE' END,
                       CASE WHEN auth.three_ds_result = 'AUTHENTICATED' THEN TRUE ELSE FALSE END,
                       CASE WHEN rf.id IS NULL THEN FALSE ELSE TRUE END
                  FROM chargebacks cb
                  JOIN authorizations auth
                    ON auth.merchant_id = cb.merchant_id
                   AND auth.id = cb.authorization_id
                  LEFT JOIN refunds rf
                    ON rf.merchant_id = cb.merchant_id
                   AND rf.payment_intent_id = cb.payment_intent_id
                 WHERE cb.merchant_id = ? AND cb.id = ?
                """, resultSet -> resultSet.next() ? new EvidenceFacts(resultSet.getString(1),
                        resultSet.getString(2), resultSet.getBoolean(3), delivery, communication,
                        resultSet.getBoolean(4), device) : null, merchantId, chargebackId);
        if (facts == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispute workflow was not found");
        }
        return facts;
    }

    private Requirements requirements(String reasonCode, EvidenceFacts facts) {
        RequirementDefinition definition = jdbcTemplate.query("""
                SELECT require_authentication_or_device, require_delivery_confirmation,
                       require_customer_communication, require_refund_history
                  FROM dispute_reason_deadline_definitions WHERE reason_code = ?
                """, resultSet -> resultSet.next() ? new RequirementDefinition(
                        resultSet.getBoolean(1), resultSet.getBoolean(2),
                        resultSet.getBoolean(3), resultSet.getBoolean(4)) : null, reasonCode);
        if (definition == null) {
            throw new IllegalStateException("Dispute requirement definition is unavailable");
        }
        List<String> missing = new ArrayList<>();
        if (definition.authenticationOrDevice()
                && !facts.threeDsAuthenticated() && !facts.deviceContinuity()) {
            missing.add("AUTHENTICATION_OR_DEVICE_CONTINUITY");
        }
        if (definition.delivery() && !facts.deliveryConfirmed()) {
            missing.add("DELIVERY_CONFIRMATION");
        }
        if (definition.communication() && !facts.customerCommunicationPresent()) {
            missing.add("CUSTOMER_COMMUNICATION");
        }
        if (definition.refund() && !facts.refundHistoryPresent()) {
            missing.add("REFUND_HISTORY");
        }
        return new Requirements(List.copyOf(missing));
    }

    private String canonicalEvidence(int version, EvidenceFacts facts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "dispute_evidence_v1");
        value.put("version", version);
        value.put("avsResult", facts.avsResult());
        value.put("cvcResult", facts.cvcResult());
        value.put("threeDsAuthenticated", facts.threeDsAuthenticated());
        value.put("deliveryConfirmed", facts.deliveryConfirmed());
        value.put("customerCommunicationPresent", facts.customerCommunicationPresent());
        value.put("refundHistoryPresent", facts.refundHistoryPresent());
        value.put("deviceContinuity", facts.deviceContinuity());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Dispute evidence cannot be canonicalized", exception);
        }
    }

    private WorkflowView requireWorkflow(UUID merchantId, UUID chargebackId, boolean lock) {
        if (lock) {
            UUID lockedId = jdbcTemplate.query("""
                    SELECT id FROM dispute_workflows
                     WHERE merchant_id = ? AND chargeback_id = ? FOR UPDATE
                    """, resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                    merchantId, chargebackId);
            if (lockedId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispute workflow was not found");
            }
        }
        WorkflowView workflow = jdbcTemplate.query(workflowSelect()
                        + " WHERE workflow.merchant_id = ? AND workflow.chargeback_id = ?",
                resultSet -> resultSet.next() ? mapWorkflow(resultSet) : null, merchantId, chargebackId);
        if (workflow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispute workflow was not found");
        }
        return workflow;
    }

    private WorkflowView requireWorkflowById(UUID workflowId) {
        WorkflowView workflow = jdbcTemplate.query(workflowSelect() + " WHERE workflow.id = ?",
                resultSet -> resultSet.next() ? mapWorkflow(resultSet) : null, workflowId);
        if (workflow == null) {
            throw new IllegalStateException("Locked dispute workflow disappeared");
        }
        return workflow;
    }

    private String workflowSelect() {
        return """
                SELECT workflow.id, workflow.merchant_id, workflow.chargeback_id,
                       workflow.reason_code, workflow.due_at, workflow.lifecycle_status,
                       workflow.readiness_status, workflow.missing_requirements,
                       workflow.latest_evidence_version_id, latest.version_number,
                       latest.evidence_digest, workflow.submitted_evidence_version_id,
                       workflow.projection_version, workflow.created_at, workflow.updated_at,
                       workflow.submitted_at, workflow.expired_at, workflow.resolved_at
                  FROM dispute_workflows workflow
                  LEFT JOIN dispute_evidence_versions latest
                    ON latest.merchant_id = workflow.merchant_id
                   AND latest.id = workflow.latest_evidence_version_id
                """;
    }

    private WorkflowView mapWorkflow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new WorkflowView(resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                resultSet.getObject(3, UUID.class), resultSet.getString(4),
                resultSet.getTimestamp(5).toInstant(), DisputeWorkflowStatus.valueOf(resultSet.getString(6)),
                resultSet.getString(7), split(resultSet.getString(8)),
                resultSet.getObject(9, UUID.class), resultSet.getObject(10, Integer.class),
                resultSet.getString(11), resultSet.getObject(12, UUID.class), resultSet.getLong(13),
                resultSet.getTimestamp(14).toInstant(), resultSet.getTimestamp(15).toInstant(),
                instant(resultSet.getTimestamp(16)), instant(resultSet.getTimestamp(17)),
                instant(resultSet.getTimestamp(18)));
    }

    private OutcomeView findOutcomeByEvent(UUID merchantId, String eventKey) {
        return jdbcTemplate.query(outcomeSelect() + " WHERE merchant_id = ? AND external_event_key = ?",
                resultSet -> resultSet.next() ? mapOutcome(resultSet) : null, merchantId, eventKey);
    }

    private OutcomeView requireOutcome(UUID merchantId, UUID id) {
        OutcomeView outcome = jdbcTemplate.query(outcomeSelect() + " WHERE merchant_id = ? AND id = ?",
                resultSet -> resultSet.next() ? mapOutcome(resultSet) : null, merchantId, id);
        if (outcome == null) {
            throw new IllegalStateException("Committed dispute outcome is unavailable");
        }
        return outcome;
    }

    private String outcomeSelect() {
        return """
                SELECT id, dispute_workflow_id, submitted_evidence_version_id, external_event_key,
                       request_digest, outcome, source_attestation, occurred_at, received_at
                  FROM dispute_outcomes
                """;
    }

    private OutcomeView mapOutcome(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new OutcomeView(resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                resultSet.getObject(3, UUID.class), resultSet.getString(4), resultSet.getString(5),
                DisputeOutcome.valueOf(resultSet.getString(6)), resultSet.getString(7),
                resultSet.getTimestamp(8).toInstant(), resultSet.getTimestamp(9).toInstant());
    }

    private void insertOperatorEvent(RiskOperatorContext context, UUID workflowId,
            String type, UUID evidenceId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO dispute_workflow_events
                    (id, merchant_id, dispute_workflow_id, event_type, evidence_version_id,
                     actor_operator_id, source, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RISK_OPERATOR', ?)
                """, UUID.randomUUID(), context.merchantId(), workflowId, type, evidenceId,
                context.operatorId(), Timestamp.from(now));
    }

    private void insertSystemOutcomeEvent(UUID merchantId, UUID workflowId, UUID outcomeId,
            DisputeOutcome outcome, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO dispute_workflow_events
                    (id, merchant_id, dispute_workflow_id, event_type, outcome_id, source, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'SIMULATOR', ?)
                """, UUID.randomUUID(), merchantId, workflowId, "OUTCOME_" + outcome.name(),
                outcomeId, Timestamp.from(now));
    }

    @Transactional
    public void authorizeEvidenceRevisionReplay(RiskOperatorContext context, UUID chargebackId) {
        RiskOperator actor = liveActorForUpdate(context);
        requireManager(actor);
        requireWorkflow(context.merchantId(), chargebackId, true);
    }

    @Transactional
    public void authorizeSubmissionReplay(RiskOperatorContext context, UUID chargebackId) {
        RiskOperator actor = liveActorForUpdate(context);
        requireManager(actor);
        requireWorkflow(context.merchantId(), chargebackId, true);
    }

    private void requireReader(RiskOperatorContext context) {
        if (context.role() != RiskOperatorRole.VIEWER
                && context.role() != RiskOperatorRole.ANALYST
                && context.role() != RiskOperatorRole.RISK_MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A risk-operator role is required");
        }
    }

    private RiskOperator liveActorForUpdate(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive)
                .orElseThrow(DisputeOperationsService::riskResourceNotFound);
        RiskOperatorCredential credential = credentialRepository
                .findByMerchantIdAndRiskOperatorIdForUpdate(
                        context.merchantId(), context.operatorId())
                .filter(candidate -> candidate.getDisabledAt() == null)
                .orElseThrow(DisputeOperationsService::riskResourceNotFound);
        if (!credential.getRiskOperatorId().equals(actor.getId())
                || actor.getRole() != context.role()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Risk operator credential is stale");
        }
        return actor;
    }

    private void requireManager(RiskOperator actor) {
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RISK_MANAGER role is required");
        }
    }

    private static ResponseStatusException riskResourceNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk resource was not found");
    }

    private List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record WorkflowView(UUID id, UUID merchantId, UUID chargebackId, String reasonCode,
            Instant dueAt, DisputeWorkflowStatus lifecycleStatus, String readinessStatus,
            List<String> missingRequirements, UUID latestEvidenceVersionId,
            Integer latestEvidenceVersion, String latestEvidenceDigest,
            UUID submittedEvidenceVersionId, long projectionVersion, Instant createdAt,
            Instant updatedAt, Instant submittedAt, Instant expiredAt, Instant resolvedAt) { }

    public record OutcomeView(UUID id, UUID workflowId, UUID submittedEvidenceVersionId,
            String externalEventKey, String requestDigest, DisputeOutcome outcome,
            String sourceAttestation, Instant occurredAt, Instant receivedAt) { }

    private record EvidenceFacts(String avsResult, String cvcResult, boolean threeDsAuthenticated,
            boolean deliveryConfirmed, boolean customerCommunicationPresent,
            boolean refundHistoryPresent, boolean deviceContinuity) { }

    private record RequirementDefinition(boolean authenticationOrDevice, boolean delivery,
            boolean communication, boolean refund) { }

    private record Requirements(List<String> missing) { }
}
