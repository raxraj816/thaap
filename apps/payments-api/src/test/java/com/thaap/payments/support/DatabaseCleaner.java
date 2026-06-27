package com.thaap.payments.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCleaner {

    private static final String[] TABLES = {
            "audit_logs",
            "webhook_deliveries",
            "ledger_entries",
            "authorization_voids",
            "authorizations",
            "feature_snapshots",
            "fraud_decisions",
            "idempotency_records",
            "payment_attempts",
            "payment_methods",
            "payment_intents",
            "customers",
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
