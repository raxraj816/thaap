package com.thaap.payments.simulation;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("riskSimulation")
public class RiskSimulationHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final boolean workerEnabled;
    private final boolean creationEnabled;

    public RiskSimulationHealthIndicator(JdbcTemplate jdbcTemplate,
            @Value("${thaap.simulation.worker-enabled:true}") boolean workerEnabled,
            @Value("${thaap.simulation.creation-enabled:true}") boolean creationEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.workerEnabled = workerEnabled;
        this.creationEnabled = creationEnabled;
    }

    @Override
    public Health health() {
        if (creationEnabled && !workerEnabled) {
            return Health.down().withDetail("cause", "CREATION_ENABLED_WITHOUT_WORKER").build();
        }
        try {
            List<String> missing = jdbcTemplate.queryForList("""
                    SELECT required.table_name
                      FROM (VALUES ('risk_simulation_runs'), ('risk_simulation_results'),
                                   ('risk_simulation_reports'), ('batch_job_instance'),
                                   ('batch_job_execution'), ('batch_step_execution')) required(table_name)
                     WHERE NOT EXISTS (
                           SELECT 1 FROM information_schema.tables actual
                            WHERE LOWER(actual.table_name) = required.table_name)
                    """, String.class);
            if (!missing.isEmpty()) {
                return Health.down().withDetail("cause", "SIMULATION_SCHEMA_INCOMPLETE").build();
            }
            return Health.up().withDetail("worker", workerEnabled ? "ENABLED" : "DISABLED")
                    .withDetail("creation", creationEnabled ? "ENABLED" : "DISABLED").build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "SIMULATION_SCHEMA_UNAVAILABLE").build();
        }
    }
}
