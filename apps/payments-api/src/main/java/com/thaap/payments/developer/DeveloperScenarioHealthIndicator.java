package com.thaap.payments.developer;

import com.thaap.payments.processor.SimulatorProcessorCatalog;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("developerScenarios")
public class DeveloperScenarioHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final SimulatorProcessorCatalog processorCatalog;

    public DeveloperScenarioHealthIndicator(JdbcTemplate jdbcTemplate,
            SimulatorProcessorCatalog processorCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.processorCatalog = processorCatalog;
    }

    @Override
    public Health health() {
        try {
            Integer incompatibleRuns = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM developer_scenario_runs
                     WHERE catalogue_version <> 'developer_scenarios_v1'
                    """, Integer.class);
            if (incompatibleRuns == null || incompatibleRuns != 0
                    || processorCatalog.documentedFixtures().size() != 9) {
                return Health.down().withDetail("cause", "DEVELOPER_SCENARIO_CATALOGUE_INCOMPATIBLE").build();
            }
            return Health.up().withDetail("catalogue", DeveloperScenarioService.CATALOGUE_VERSION)
                    .withDetail("testCards", processorCatalog.documentedFixtures().size()).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "DEVELOPER_SCENARIO_SCHEMA_UNAVAILABLE").build();
        }
    }
}
