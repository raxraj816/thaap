package com.thaap.payments.dispute;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("disputeOperations")
public class DisputeOperationsHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DisputeOperationsHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer definitions = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dispute_reason_deadline_definitions
                     WHERE catalogue_version = 'dispute_deadlines_v1'
                    """, Integer.class);
            Integer invalidWorkflows = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dispute_workflows
                     WHERE deadline_catalogue_version <> 'dispute_deadlines_v1'
                    """, Integer.class);
            if (definitions == null || definitions != ChargebackReasonCode.values().length
                    || invalidWorkflows == null || invalidWorkflows != 0) {
                return Health.down().withDetail("cause", "DISPUTE_CATALOGUE_INCOMPATIBLE").build();
            }
            return Health.up().withDetail("catalogue", "dispute_deadlines_v1")
                    .withDetail("definitions", definitions).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "DISPUTE_SCHEMA_UNAVAILABLE").build();
        }
    }
}
