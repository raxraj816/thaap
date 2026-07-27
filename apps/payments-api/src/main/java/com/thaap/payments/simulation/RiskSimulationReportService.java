package com.thaap.payments.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.security.SecretHasher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskSimulationReportService {

    private static final long MINIMUM_SEGMENT_POPULATION = 10;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration reportFreshness;

    public RiskSimulationReportService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            Clock clock, @Value("${thaap.simulation.report-freshness:24h}") Duration reportFreshness) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.reportFreshness = reportFreshness;
    }

    @Transactional
    public void finalizeSuccess(UUID runId) {
        RunIdentity run = requireRunningRun(runId);
        Aggregate aggregate = aggregate(runId, run);
        if (aggregate.processed() != run.processed()
                || aggregate.eligible() != run.eligible()
                || aggregate.excluded() != run.excluded()) {
            throw new IllegalStateException("Simulation report does not reconcile to durable progress");
        }
        Instant completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = completedAt.plus(reportFreshness);
        Map<String, Object> report = canonicalReport(runId, run, aggregate);
        String canonicalJson = write(report);
        String digest = SecretHasher.sha256(canonicalJson);
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_reports
                    (id, merchant_id, simulation_run_id, canonical_report_json,
                     report_digest, processed_count, eligible_count, excluded_count,
                     protected_suppression_count, blocking_data_quality_count,
                     labelled_count, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), run.merchantId(), runId, canonicalJson, digest,
                aggregate.processed(), aggregate.eligible(), aggregate.excluded(),
                aggregate.protectedSuppressions(), aggregate.excluded(), aggregate.labelled(),
                Timestamp.from(completedAt), Timestamp.from(expiresAt));
        insertSegments(runId, run, completedAt);
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_events
                    (id, merchant_id, simulation_run_id, event_type, execution_token,
                     safe_reason_code, occurred_at)
                VALUES (?, ?, ?, 'SUCCEEDED', ?, 'SIMULATION_COMPLETED', ?)
                """, UUID.randomUUID(), run.merchantId(), runId, run.executionToken(),
                Timestamp.from(completedAt));
        int changed = jdbcTemplate.update("""
                UPDATE risk_simulation_runs
                   SET lifecycle_status = 'SUCCEEDED', completed_at = ?, report_expires_at = ?,
                       failure_stage = NULL, failure_code = NULL,
                       projection_version = projection_version + 1
                 WHERE id = ? AND merchant_id = ? AND lifecycle_status = 'RUNNING'
                   AND execution_token = ?
                """, Timestamp.from(completedAt), Timestamp.from(expiresAt), runId,
                run.merchantId(), run.executionToken());
        if (changed != 1) {
            throw new IllegalStateException("Simulation state changed before report finalization");
        }
    }

    private RunIdentity requireRunningRun(UUID runId) {
        RunIdentity run = jdbcTemplate.query("""
                SELECT merchant_id, execution_token, candidate_digest, query_digest,
                       processed_count, eligible_count, excluded_count
                  FROM risk_simulation_runs
                 WHERE id = ? AND lifecycle_status = 'RUNNING' FOR UPDATE
                """, resultSet -> resultSet.next()
                        ? new RunIdentity(resultSet.getObject(1, UUID.class),
                                resultSet.getObject(2, UUID.class), resultSet.getString(3),
                                resultSet.getString(4), resultSet.getLong(5),
                                resultSet.getLong(6), resultSet.getLong(7)) : null,
                runId);
        if (run == null) {
            throw new IllegalStateException("Simulation cannot be finalized from its current state");
        }
        return run;
    }

    private Aggregate aggregate(UUID runId, RunIdentity run) {
        return jdbcTemplate.query("""
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN eligibility_status = 'ELIGIBLE' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN eligibility_status = 'EXCLUDED' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN protected_suppression_attempted THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN eligibility_status = 'ELIGIBLE'
                                         AND label_outcome IN ('FRAUD', 'LEGITIMATE') THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN eligibility_status = 'ELIGIBLE' THEN authorization_amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN eligibility_status = 'ELIGIBLE' THEN capture_amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN eligibility_status = 'ELIGIBLE' THEN refund_amount ELSE 0 END), 0)
                  FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                """, resultSet -> {
                    resultSet.next();
                    return new Aggregate(resultSet.getLong(1), resultSet.getLong(2),
                            resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5),
                            resultSet.getLong(6), resultSet.getLong(7), resultSet.getLong(8));
                }, run.merchantId(), runId);
    }

    private Map<String, Object> canonicalReport(UUID runId, RunIdentity run, Aggregate aggregate) {
        Map<String, Long> population = new TreeMap<>();
        population.put("eligible", aggregate.eligible());
        population.put("excluded", aggregate.excluded());
        population.put("processed", aggregate.processed());
        Map<String, Long> money = new TreeMap<>();
        money.put("authorized", aggregate.authorizationAmount());
        money.put("captured", aggregate.captureAmount());
        money.put("refunded", aggregate.refundAmount());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "historical_risk_simulation_report_v1");
        report.put("candidateDigest", run.candidateDigest());
        report.put("queryDigest", run.queryDigest());
        report.put("population", population);
        report.put("labelledCount", aggregate.labelled());
        report.put("blockingDataQualityCount", aggregate.excluded());
        report.put("protectedSuppressionCount", aggregate.protectedSuppressions());
        report.put("eligibleMoney", money);
        report.put("actionTransitions", grouped(runId, run, """
                SELECT original_action || '->' || candidate_action, COUNT(*)
                  FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                   AND eligibility_status = 'ELIGIBLE'
                 GROUP BY original_action, candidate_action
                """));
        report.put("exclusionReasons", grouped(runId, run, """
                SELECT exclusion_reason, COUNT(*) FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                   AND eligibility_status = 'EXCLUDED'
                 GROUP BY exclusion_reason
                """));
        report.put("labelOutcomes", grouped(runId, run, """
                SELECT label_outcome, COUNT(*) FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                 GROUP BY label_outcome
                """));
        report.put("originBreakdown", grouped(runId, run, """
                SELECT traffic_origin, COUNT(*) FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                 GROUP BY traffic_origin
                """));
        report.put("labelledFraudCaught", count(runId, run,
                "label_outcome = 'FRAUD' AND candidate_action = 'BLOCK'"));
        report.put("labelledFraudMissed", count(runId, run,
                "label_outcome = 'FRAUD' AND candidate_action <> 'BLOCK'"));
        report.put("knownLegitimateFriction", count(runId, run,
                "label_outcome = 'LEGITIMATE' AND candidate_action <> 'ALLOW'"));
        return report;
    }

    private Map<String, Long> grouped(UUID runId, RunIdentity run, String sql) {
        Map<String, Long> values = new TreeMap<>();
        jdbcTemplate.query(sql, resultSet -> {
            while (resultSet.next()) {
                values.put(resultSet.getString(1), resultSet.getLong(2));
            }
            return null;
        }, run.merchantId(), runId);
        return values;
    }

    private long count(UUID runId, RunIdentity run, String condition) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                   AND eligibility_status = 'ELIGIBLE' AND (""" + condition + ")",
                Long.class, run.merchantId(), runId);
        return value == null ? 0 : value;
    }

    private void insertSegments(UUID simulationRunId, RunIdentity run, Instant createdAt) {
        jdbcTemplate.query("""
                SELECT traffic_origin, COUNT(*),
                       SUM(CASE WHEN eligibility_status = 'ELIGIBLE' THEN 1 ELSE 0 END)
                  FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                 GROUP BY traffic_origin HAVING COUNT(*) >= ?
                 ORDER BY traffic_origin
                """, resultSet -> {
                    while (resultSet.next()) {
                        Map<String, Long> transition = new TreeMap<>();
                        transition.put("eligible", resultSet.getLong(3));
                        transition.put("population", resultSet.getLong(2));
                        jdbcTemplate.update("""
                                INSERT INTO risk_simulation_segments
                                    (id, merchant_id, simulation_run_id, dimension_code,
                                     segment_code, population_count, eligible_count,
                                     transition_json, created_at)
                                VALUES (?, ?, ?, 'TRAFFIC_ORIGIN', ?, ?, ?, ?, ?)
                                """, UUID.randomUUID(), run.merchantId(), simulationRunId,
                                resultSet.getString(1), resultSet.getLong(2), resultSet.getLong(3),
                                write(transition), Timestamp.from(createdAt));
                    }
                    return null;
                }, run.merchantId(), simulationRunId, MINIMUM_SEGMENT_POPULATION);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Simulation report cannot be canonicalized", exception);
        }
    }

    private record RunIdentity(UUID merchantId, UUID executionToken, String candidateDigest,
            String queryDigest, long processed, long eligible, long excluded) { }

    private record Aggregate(long processed, long eligible, long excluded,
            long protectedSuppressions, long labelled, long authorizationAmount,
            long captureAmount, long refundAmount) { }
}
