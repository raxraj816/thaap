package com.thaap.payments.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCleaner {

    private static final String[] TABLES = {
            "audit_logs",
            "webhook_delivery_attempts",
            "webhook_deliveries",
            "webhook_events",
            "webhook_endpoint_event_subscriptions",
            "webhook_endpoint_creation_identities",
            "webhook_endpoint_creation_records",
            "ledger_entries",
            "refunds",
            "authorization_voids",
            "authorizations",
            "manual_review_cases",
            "merchant_creation_identities",
            "merchant_creation_records",
            "idempotency_records",
            "payment_attempts",
            "payment_methods",
            "payment_intents",
            "customers",
            "review_cases",
            "webhook_endpoints",
            "merchants"
    };

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clean() {
        String databaseProduct = jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
        if ("H2".equals(databaseProduct)) {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
            try {
                jdbcTemplate.execute("TRUNCATE TABLE feature_values");
                jdbcTemplate.execute("TRUNCATE TABLE feature_snapshots");
                jdbcTemplate.execute("TRUNCATE TABLE rule_execution_results");
                jdbcTemplate.execute("TRUNCATE TABLE manual_review_cases");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_decisions");
            } finally {
                jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
        } else {
            jdbcTemplate.execute("TRUNCATE TABLE rule_execution_results, manual_review_cases, "
                    + "feature_values, feature_snapshots, fraud_decisions");
        }
        for (var table : TABLES) {
            if ("manual_review_cases".equals(table)) {
                continue;
            }
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("""
                UPDATE webhook_dispatch_cursor
                SET last_merchant_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """);
    }
}
