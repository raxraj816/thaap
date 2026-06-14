package com.thaap.payments.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCleaner {

    private static final String[] TABLES = {
            "webhook_deliveries",
            "payment_attempts",
            "idempotency_records",
            "payment_methods",
            "payment_intents",
            "customers",
            "fraud_decisions",
            "fraud_rules",
            "review_cases",
            "webhook_endpoints",
            "merchants"
    };

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clean() {
        for (var table : TABLES) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
