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
            "card_testing_incident_resolutions",
            "incident_evidence_links",
            "card_testing_incidents",
            "dispute_evidence_packets",
            "dispute_offset_allocations",
            "chargebacks",
            "review_refund_actions",
            "refunds",
            "authorization_voids",
            "risk_control_evaluations",
            "fraud_list_evaluations",
            "risk_contexts",
            "authentication_challenges",
            "authorizations",
            "fraud_labels",
            "review_resolutions",
            "review_annotations",
            "review_assignment_events",
            "manual_review_cases",
            "post_authorization_signals",
            "fraud_list_lifecycle_events",
            "fraud_list_entries",
            "fraud_list_versions",
            "fraud_lists",
            "merchant_risk_policy_events",
            "merchant_risk_policy_thresholds",
            "merchant_risk_policy_versions",
            "merchant_risk_profile_events",
            "merchant_risk_profile_versions",
            "merchant_risk_configuration_guards",
            "risk_operator_credentials",
            "risk_operators",
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
        boolean h2 = "H2".equals(databaseProduct);
        if (h2) {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
            try {
                jdbcTemplate.execute("TRUNCATE TABLE feature_values");
                jdbcTemplate.execute("TRUNCATE TABLE card_testing_incident_resolutions");
                jdbcTemplate.execute("TRUNCATE TABLE incident_evidence_links");
                jdbcTemplate.execute("TRUNCATE TABLE card_testing_incidents");
                jdbcTemplate.execute("TRUNCATE TABLE feature_snapshots");
                jdbcTemplate.execute("TRUNCATE TABLE rule_execution_results");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_labels");
                jdbcTemplate.execute("TRUNCATE TABLE velocity_observations");
                jdbcTemplate.execute("TRUNCATE TABLE risk_control_evaluations");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_list_evaluations");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_list_lifecycle_events");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_list_entries");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_list_versions");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_lists");
                jdbcTemplate.execute("TRUNCATE TABLE risk_contexts");
                jdbcTemplate.execute("TRUNCATE TABLE authentication_challenges");
                jdbcTemplate.execute("TRUNCATE TABLE review_resolutions");
                jdbcTemplate.execute("TRUNCATE TABLE review_refund_actions");
                jdbcTemplate.execute("TRUNCATE TABLE review_annotations");
                jdbcTemplate.execute("TRUNCATE TABLE review_assignment_events");
                jdbcTemplate.execute("TRUNCATE TABLE manual_review_cases");
                jdbcTemplate.execute("TRUNCATE TABLE post_authorization_signals");
                jdbcTemplate.execute("TRUNCATE TABLE fraud_decisions");
            } finally {
                jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
        } else {
            jdbcTemplate.execute("TRUNCATE TABLE audit_logs, webhook_delivery_attempts, webhook_deliveries, "
                    + "webhook_events, webhook_endpoint_event_subscriptions, webhook_endpoint_creation_identities, "
                    + "webhook_endpoint_creation_records, ledger_entries, dispute_evidence_packets, "
                    + "card_testing_incident_resolutions, incident_evidence_links, card_testing_incidents, "
                    + "dispute_offset_allocations, chargebacks, review_refund_actions, refunds, "
                    + "post_authorization_signals, authorization_voids, "
                    + "velocity_observations, risk_control_evaluations, fraud_list_evaluations, risk_contexts, authentication_challenges, authorizations, fraud_labels, review_resolutions, review_annotations, "
                    + "review_assignment_events, manual_review_cases, risk_operator_credentials, risk_operators, "
                    + "fraud_list_lifecycle_events, fraud_list_entries, fraud_list_versions, fraud_lists, "
                    + "merchant_risk_policy_events, merchant_risk_policy_thresholds, merchant_risk_policy_versions, "
                    + "merchant_risk_profile_events, merchant_risk_profile_versions, merchant_risk_configuration_guards, "
                    + "merchant_creation_identities, merchant_creation_records, idempotency_records, "
                    + "rule_execution_results, feature_values, feature_snapshots, fraud_decisions, "
                    + "payment_attempts, payment_methods, payment_intents, customers, review_cases, "
                    + "webhook_endpoints, merchants CASCADE");
        }
        if (h2) {
            for (String table : TABLES) {
                if ("manual_review_cases".equals(table)) {
                    continue;
                }
                jdbcTemplate.update("DELETE FROM " + table);
            }
        }
        jdbcTemplate.update("""
                UPDATE webhook_dispatch_cursor
                SET last_merchant_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """);
    }
}
