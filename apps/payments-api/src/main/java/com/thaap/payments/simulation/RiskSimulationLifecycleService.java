package com.thaap.payments.simulation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskSimulationLifecycleService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public RiskSimulationLifecycleService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRunning(UUID runId, long jobExecutionId) {
        RunState run = lock(runId);
        if (run == null || run.status() == RiskSimulationLifecycleStatus.CANCELED) {
            return false;
        }
        if (run.status() != RiskSimulationLifecycleStatus.QUEUED
                && run.status() != RiskSimulationLifecycleStatus.RUNNING) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int changed = jdbcTemplate.update("""
                UPDATE risk_simulation_runs
                   SET lifecycle_status = 'RUNNING', job_execution_id = ?,
                       started_at = COALESCE(started_at, ?),
                       failure_stage = NULL, failure_code = NULL,
                       projection_version = projection_version + 1
                 WHERE id = ? AND lifecycle_status IN ('QUEUED', 'RUNNING')
                """, jobExecutionId, Timestamp.from(now), runId);
        if (changed != 1) {
            return false;
        }
        if (run.status() == RiskSimulationLifecycleStatus.QUEUED) {
            jdbcTemplate.update("""
                    INSERT INTO risk_simulation_events
                        (id, merchant_id, simulation_run_id, event_type, execution_token,
                         safe_reason_code, occurred_at)
                    VALUES (?, ?, ?, 'STARTED', ?, 'SIMULATION_STARTED', ?)
                    """, UUID.randomUUID(), run.merchantId(), runId, run.executionToken(),
                    Timestamp.from(now));
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRecoverableFailure(UUID runId, String stage, String code) {
        RunState run = lock(runId);
        if (run == null || run.status() != RiskSimulationLifecycleStatus.RUNNING) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE risk_simulation_runs
                   SET failure_stage = ?, failure_code = ?,
                       projection_version = projection_version + 1
                 WHERE id = ? AND lifecycle_status = 'RUNNING'
                """, stage, code, runId);
    }

    private RunState lock(UUID runId) {
        return jdbcTemplate.query("""
                SELECT merchant_id, execution_token, lifecycle_status
                  FROM risk_simulation_runs WHERE id = ? FOR UPDATE
                """, resultSet -> resultSet.next()
                        ? new RunState(resultSet.getObject(1, UUID.class),
                                resultSet.getObject(2, UUID.class),
                                RiskSimulationLifecycleStatus.valueOf(resultSet.getString(3))) : null,
                runId);
    }

    private record RunState(UUID merchantId, UUID executionToken,
            RiskSimulationLifecycleStatus status) { }
}
