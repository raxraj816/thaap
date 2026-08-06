package com.thaap.payments.simulation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.fraud.DeterministicConfirmDecisionEngine;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.security.SecretHasher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskSimulationService {

    private static final TypeReference<Map<String, Object>> REPORT_MAP = new TypeReference<>() { };

    public static final String EVALUATOR_VERSION = "historical_policy_v1";
    public static final String TAXONOMY_VERSION = "fraud_taxonomy_v1";
    public static final String LABEL_POLICY = "CURRENT_AT_CUTOFF";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RiskSimulationCandidateSnapshotService snapshotService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final boolean creationEnabled;
    private final int maximumCohortDays;

    public RiskSimulationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            RiskSimulationCandidateSnapshotService snapshotService, AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher, Clock clock,
            @Value("${thaap.simulation.creation-enabled:true}") boolean creationEnabled,
            @Value("${thaap.simulation.maximum-cohort-days:365}") int maximumCohortDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.creationEnabled = creationEnabled;
        this.maximumCohortDays = maximumCohortDays;
    }

    @Transactional
    public SimulationView create(RiskOperatorContext context, RiskSimulationCandidateType candidateType,
            UUID candidateVersionId, Instant cohortStart, Instant cohortEnd, Instant asOf,
            Instant labelCutoff, RiskSimulationTrafficOriginPolicy originPolicy, String requestId) {
        if (!creationEnabled) {
            throw new PublicApiException(PublicApiErrorDescriptor.HISTORICAL_SIMULATION_CREATION_DISABLED, "Historical simulation creation is disabled");
        }
        authorizeManagerForUpdate(context);
        if (candidateType == null || candidateVersionId == null || originPolicy == null) {
            throw badRequest("Simulation candidate and traffic origin policy are required");
        }
        Instant now = micros(clock.instant());
        Instant normalizedStart = requireMicros(cohortStart, "cohortStart");
        Instant normalizedEnd = requireMicros(cohortEnd, "cohortEnd");
        Instant normalizedAsOf = requireMicros(asOf, "asOf");
        Instant normalizedCutoff = requireMicros(labelCutoff, "labelCutoff");
        if (!normalizedStart.isBefore(normalizedEnd)
                || normalizedEnd.isAfter(normalizedAsOf)
                || normalizedAsOf.isAfter(now)
                || normalizedCutoff.isAfter(normalizedAsOf)
                || Duration.between(normalizedStart, normalizedEnd)
                        .compareTo(Duration.ofDays(maximumCohortDays)) > 0) {
            throw badRequest("Simulation time boundary is invalid");
        }
        RiskSimulationCandidateSnapshotService.FrozenCandidate candidate = snapshotService.freeze(
                context.merchantId(), candidateType, candidateVersionId);
        String queryIdentity = String.join("|", context.merchantId().toString(),
                candidate.digest(), normalizedStart.toString(), normalizedEnd.toString(),
                normalizedAsOf.toString(), normalizedCutoff.toString(), originPolicy.name(),
                LABEL_POLICY, EVALUATOR_VERSION, FeatureCatalogue.VERSION,
                DeterministicConfirmDecisionEngine.RULESET_VERSION, TAXONOMY_VERSION);
        String queryDigest = SecretHasher.sha256(queryIdentity);
        UUID runId = UUID.randomUUID();
        UUID executionToken = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_runs
                    (id, merchant_id, created_by_operator_id, candidate_type,
                     candidate_version_id, candidate_schema_version, candidate_json,
                     candidate_digest, cohort_start, cohort_end, as_of,
                     traffic_origin_policy, label_cutoff, label_resolution_policy,
                     evaluator_version, feature_catalogue_version, rule_catalogue_version,
                     taxonomy_version, query_digest, lifecycle_status, execution_token,
                     created_at, queued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'QUEUED', ?, ?, ?)
                """, runId, context.merchantId(), context.operatorId(), candidateType.name(),
                candidateVersionId, candidate.schemaVersion(),
                candidate.canonicalJson(), candidate.digest(), Timestamp.from(normalizedStart),
                Timestamp.from(normalizedEnd), Timestamp.from(normalizedAsOf), originPolicy.name(),
                Timestamp.from(normalizedCutoff), LABEL_POLICY, EVALUATOR_VERSION,
                FeatureCatalogue.VERSION, DeterministicConfirmDecisionEngine.RULESET_VERSION, TAXONOMY_VERSION,
                queryDigest, executionToken, Timestamp.from(now), Timestamp.from(now));
        insertEvent(context.merchantId(), runId, "CREATED", context.operatorId(), null,
                "SIMULATION_CREATED", now);
        insertEvent(context.merchantId(), runId, "QUEUED", context.operatorId(), null,
                "SIMULATION_QUEUED", now);
        auditLogService.recordRiskOperatorAction(context, requestId, "risk_simulation.created",
                "RiskSimulation", runId, "HISTORICAL_POLICY_SIMULATION", Map.of(),
                Map.of("candidateType", candidateType.name(), "originPolicy", originPolicy.name(),
                        "status", "QUEUED"));
        eventPublisher.publishEvent(new RiskSimulationQueuedEvent(runId));
        return requireView(context.merchantId(), runId);
    }

    @Transactional(readOnly = true)
    public SimulationView get(RiskOperatorContext context, UUID runId) {
        SimulationView view = requireView(context.merchantId(), runId);
        if (view.lifecycleStatus() != RiskSimulationLifecycleStatus.SUCCEEDED
                && !canReadProgress(context, runId)) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIMULATION_PROGRESS_ACCESS_FORBIDDEN, "Only the creator or a risk manager can read simulation progress");
        }
        return view;
    }

    @Transactional(readOnly = true)
    public SimulationReportView report(RiskOperatorContext context, UUID runId) {
        SimulationView run = requireView(context.merchantId(), runId);
        if (run.lifecycleStatus() != RiskSimulationLifecycleStatus.SUCCEEDED) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIMULATION_REPORT_UNAVAILABLE, "Simulation report is available only after success");
        }
        return jdbcTemplate.query("""
                SELECT report_digest, canonical_report_json, created_at, expires_at
                  FROM risk_simulation_reports
                 WHERE merchant_id = ? AND simulation_run_id = ?
                """, resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Successful simulation report is missing");
                    }
                    return new SimulationReportView(run, resultSet.getString(1),
                            readReport(resultSet.getString(2)), resultSet.getTimestamp(3).toInstant(),
                            resultSet.getTimestamp(4).toInstant());
                }, context.merchantId(), runId);
    }

    @Transactional
    public SimulationView cancel(RiskOperatorContext context, UUID runId, String requestId) {
        authorizeManagerForUpdate(context);
        SimulationView run = requireViewForUpdate(context.merchantId(), runId);
        if (run.lifecycleStatus() == RiskSimulationLifecycleStatus.CANCELED) {
            return run;
        }
        if (run.lifecycleStatus() != RiskSimulationLifecycleStatus.QUEUED
                && run.lifecycleStatus() != RiskSimulationLifecycleStatus.RUNNING) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIMULATION_NOT_CANCELABLE, "Only a queued or running simulation can be canceled");
        }
        Instant now = micros(clock.instant());
        int changed = jdbcTemplate.update("""
                UPDATE risk_simulation_runs
                   SET lifecycle_status = 'CANCELED', completed_at = ?,
                       failure_stage = 'CANCELLATION', failure_code = 'OPERATOR_CANCELED',
                       projection_version = projection_version + 1
                 WHERE merchant_id = ? AND id = ? AND lifecycle_status IN ('QUEUED', 'RUNNING')
                """, Timestamp.from(now), context.merchantId(), runId);
        if (changed != 1) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIMULATION_STATE_CHANGED, "Simulation state changed before cancellation");
        }
        insertEvent(context.merchantId(), runId, "CANCELED", context.operatorId(), null,
                "OPERATOR_CANCELED", now);
        auditLogService.recordRiskOperatorAction(context, requestId, "risk_simulation.canceled",
                "RiskSimulation", runId, "OPERATOR_CANCELED",
                Map.of("status", run.lifecycleStatus().name()), Map.of("status", "CANCELED"));
        return requireView(context.merchantId(), runId);
    }

    @Transactional
    public void authorizeCreateReplay(RiskOperatorContext context) {
        authorizeManagerForUpdate(context);
    }

    @Transactional
    public void authorizeCancelReplay(RiskOperatorContext context, UUID runId) {
        authorizeManagerForUpdate(context);
        requireViewForUpdate(context.merchantId(), runId);
    }

    private void authorizeManagerForUpdate(RiskOperatorContext context) {
        String role = jdbcTemplate.query("""
                SELECT role FROM risk_operators
                 WHERE merchant_id = ? AND id = ? AND status = 'ACTIVE'
                 FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                context.merchantId(), context.operatorId());
        if (!RiskOperatorRole.RISK_MANAGER.name().equals(role)) {
            throw new PublicApiException(PublicApiErrorDescriptor.HISTORICAL_SIMULATION_MANAGER_REQUIRED, "RISK_MANAGER role is required for historical simulation");
        }
    }

    private boolean canReadProgress(RiskOperatorContext context, UUID runId) {
        Boolean permitted = jdbcTemplate.query("""
                SELECT operator.role = 'RISK_MANAGER' OR run.created_by_operator_id = operator.id
                  FROM risk_simulation_runs run
                  JOIN risk_operators operator
                    ON operator.merchant_id = run.merchant_id AND operator.id = ?
                 WHERE run.merchant_id = ? AND run.id = ? AND operator.status = 'ACTIVE'
                """, resultSet -> resultSet.next() ? resultSet.getBoolean(1) : null,
                context.operatorId(), context.merchantId(), runId);
        return Boolean.TRUE.equals(permitted);
    }

    private SimulationView requireView(UUID merchantId, UUID runId) {
        SimulationView view = jdbcTemplate.query(viewSql(false),
                resultSet -> resultSet.next() ? mapView(resultSet) : null, merchantId, runId);
        if (view == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIMULATION_NOT_FOUND, "Simulation was not found");
        }
        return view;
    }

    private SimulationView requireViewForUpdate(UUID merchantId, UUID runId) {
        SimulationView view = jdbcTemplate.query(viewSql(true),
                resultSet -> resultSet.next() ? mapView(resultSet) : null, merchantId, runId);
        if (view == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIMULATION_NOT_FOUND, "Simulation was not found");
        }
        return view;
    }

    private String viewSql(boolean lock) {
        return """
                SELECT id, candidate_type, candidate_version_id, candidate_digest,
                       cohort_start, cohort_end, as_of, label_cutoff, traffic_origin_policy,
                       lifecycle_status, processed_count, eligible_count, excluded_count,
                       checkpoint_created_at, checkpoint_attempt_id, created_at, queued_at,
                       started_at, completed_at, report_expires_at, failure_stage, failure_code
                  FROM risk_simulation_runs
                 WHERE merchant_id = ? AND id = ?
                """ + (lock ? " FOR UPDATE" : "");
    }

    private SimulationView mapView(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Timestamp checkpoint = resultSet.getTimestamp(14);
        Timestamp started = resultSet.getTimestamp(18);
        Timestamp completed = resultSet.getTimestamp(19);
        Timestamp expires = resultSet.getTimestamp(20);
        return new SimulationView(resultSet.getObject(1, UUID.class),
                RiskSimulationCandidateType.valueOf(resultSet.getString(2)),
                resultSet.getObject(3, UUID.class), resultSet.getString(4),
                resultSet.getTimestamp(5).toInstant(), resultSet.getTimestamp(6).toInstant(),
                resultSet.getTimestamp(7).toInstant(), resultSet.getTimestamp(8).toInstant(),
                RiskSimulationTrafficOriginPolicy.valueOf(resultSet.getString(9)),
                RiskSimulationLifecycleStatus.valueOf(resultSet.getString(10)),
                resultSet.getLong(11), resultSet.getLong(12), resultSet.getLong(13),
                checkpoint == null ? null : checkpoint.toInstant(), resultSet.getObject(15, UUID.class),
                resultSet.getTimestamp(16).toInstant(), resultSet.getTimestamp(17).toInstant(),
                started == null ? null : started.toInstant(), completed == null ? null : completed.toInstant(),
                expires == null ? null : expires.toInstant(), resultSet.getString(21), resultSet.getString(22));
    }

    private void insertEvent(UUID merchantId, UUID runId, String type, UUID actorId,
            UUID executionToken, String reason, Instant occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_events
                    (id, merchant_id, simulation_run_id, event_type, actor_operator_id,
                     execution_token, safe_reason_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), merchantId, runId, type, actorId, executionToken,
                reason, Timestamp.from(occurredAt));
    }

    private Instant requireMicros(Instant value, String field) {
        if (value == null || !value.equals(micros(value))) {
            throw badRequest(field + " must be an exact UTC microsecond instant");
        }
        return value;
    }

    private Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private Map<String, Object> readReport(String value) {
        try {
            return objectMapper.readValue(value, REPORT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored simulation report is unreadable", exception);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.RISK_SIMULATION_REQUEST_INVALID, message);
    }

    public record SimulationView(UUID id, RiskSimulationCandidateType candidateType,
            UUID candidateVersionId, String candidateDigest, Instant cohortStart, Instant cohortEnd,
            Instant asOf, Instant labelCutoff, RiskSimulationTrafficOriginPolicy trafficOriginPolicy,
            RiskSimulationLifecycleStatus lifecycleStatus, long processedCount, long eligibleCount,
            long excludedCount, Instant checkpointCreatedAt, UUID checkpointAttemptId,
            Instant createdAt, Instant queuedAt, Instant startedAt, Instant completedAt,
            Instant reportExpiresAt, String failureStage, String failureCode) { }

    public record SimulationReportView(SimulationView run, String reportDigest,
            Map<String, Object> report, Instant createdAt, Instant expiresAt) { }
}
