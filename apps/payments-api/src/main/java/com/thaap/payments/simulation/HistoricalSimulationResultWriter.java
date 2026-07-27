package com.thaap.payments.simulation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

public class HistoricalSimulationResultWriter implements ItemWriter<HistoricalSimulationResult> {

    private final JdbcTemplate jdbcTemplate;
    private final UUID runId;
    private final Clock clock;
    private final RiskSimulationExecutionProbe executionProbe;

    public HistoricalSimulationResultWriter(JdbcTemplate jdbcTemplate, UUID runId, Clock clock,
            RiskSimulationExecutionProbe executionProbe) {
        this.jdbcTemplate = jdbcTemplate;
        this.runId = runId;
        this.clock = clock;
        this.executionProbe = executionProbe;
    }

    @Override
    public void write(Chunk<? extends HistoricalSimulationResult> chunk) {
        List<? extends HistoricalSimulationResult> results = chunk.getItems();
        if (results.isEmpty()) {
            return;
        }
        RunIdentity run = requireRunningRun();
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long eligible = 0;
        long excluded = 0;
        for (HistoricalSimulationResult result : results) {
            jdbcTemplate.update("""
                    INSERT INTO risk_simulation_results
                        (id, merchant_id, simulation_run_id, payment_attempt_id,
                         fraud_decision_id, decision_created_at, traffic_origin,
                         original_action, candidate_action, eligibility_status,
                         exclusion_reason, label_outcome, protected_suppression_attempted,
                         authorization_amount, capture_amount, refund_amount, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), run.merchantId(), runId, result.paymentAttemptId(),
                    result.fraudDecisionId(), Timestamp.from(result.decisionCreatedAt()),
                    result.trafficOrigin().name(), result.originalAction().name(),
                    result.candidateAction() == null ? null : result.candidateAction().name(),
                    result.eligible() ? "ELIGIBLE" : "EXCLUDED",
                    result.exclusionReason() == null ? null : result.exclusionReason().name(),
                    result.labelOutcome().name(), result.protectedSuppressionAttempted(),
                    result.authorizationAmount(), result.captureAmount(), result.refundAmount(),
                    Timestamp.from(createdAt));
            if (result.eligible()) {
                eligible++;
            } else {
                excluded++;
            }
        }
        executionProbe.afterResultWritesBeforeProgress(runId);
        HistoricalSimulationResult checkpoint = results.getLast();
        int changed = jdbcTemplate.update("""
                UPDATE risk_simulation_runs
                   SET checkpoint_created_at = ?, checkpoint_attempt_id = ?,
                       processed_count = processed_count + ?,
                       eligible_count = eligible_count + ?,
                       excluded_count = excluded_count + ?,
                       projection_version = projection_version + 1
                 WHERE id = ? AND merchant_id = ? AND lifecycle_status = 'RUNNING'
                   AND execution_token = ?
                """, Timestamp.from(checkpoint.decisionCreatedAt()), checkpoint.paymentAttemptId(),
                results.size(), eligible, excluded, runId, run.merchantId(), run.executionToken());
        if (changed != 1) {
            throw new IllegalStateException("Simulation execution lost its active run lease");
        }
    }

    private RunIdentity requireRunningRun() {
        RunIdentity run = jdbcTemplate.query("""
                SELECT merchant_id, execution_token FROM risk_simulation_runs
                 WHERE id = ? AND lifecycle_status = 'RUNNING'
                """, resultSet -> resultSet.next()
                        ? new RunIdentity(resultSet.getObject(1, UUID.class),
                                resultSet.getObject(2, UUID.class)) : null,
                runId);
        if (run == null) {
            throw new IllegalStateException("Simulation is no longer running");
        }
        return run;
    }

    private record RunIdentity(UUID merchantId, UUID executionToken) { }
}
