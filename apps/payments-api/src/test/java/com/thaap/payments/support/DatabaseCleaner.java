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
            "feature_snapshots",
            "fraud_decisions",
            "merchant_creation_identities",
            "merchant_creation_records",
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
        jdbcTemplate.update("""
                UPDATE webhook_dispatch_cursor
                SET last_merchant_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """);
    }
}
