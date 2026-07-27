package com.thaap.payments.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCleaner {

    private static final String[] TABLES = {
            "developer_scenario_events",
            "developer_scenario_steps",
            "developer_scenario_runs",
            "dispute_workflow_events",
            "dispute_outcomes",
            "dispute_evidence_versions",
            "dispute_workflows",
            "authored_rule_evaluations",
            "risk_safety_exception_events",
            "risk_safety_exception_versions",
            "authored_rule_events",
            "authored_rule_versions",
            "risk_simulation_segments",
            "risk_simulation_reports",
            "risk_simulation_results",
            "risk_simulation_events",
            "risk_simulation_runs",
            "batch_step_execution_context",
            "batch_step_execution",
            "batch_job_execution_context",
            "batch_job_execution_params",
            "batch_job_execution",
            "batch_job_instance",
            "audit_logs",
            "review_note_redaction_events",
            "review_notes",
            "review_saved_searches",
            "merchant_risk_profile_recommendations",
            "merchant_risk_metric_snapshots",
            "webhook_endpoint_verification_events",
            "webhook_endpoint_verifications",
            "webhook_redelivery_requests",
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
                jdbcTemplate.update("""
                        UPDATE webhook_endpoints
                           SET verification_status = 'NOT_REQUESTED',
                               pending_verification_id = NULL,
                               verified_url = NULL,
                               verified_signing_secret_generation = NULL,
                               verified_at = NULL
                        """);
                jdbcTemplate.execute("TRUNCATE TABLE webhook_endpoint_verification_events");
                jdbcTemplate.execute("TRUNCATE TABLE webhook_endpoint_verifications");
                jdbcTemplate.execute("TRUNCATE TABLE webhook_redelivery_requests");
                jdbcTemplate.execute("TRUNCATE TABLE developer_scenario_events");
                jdbcTemplate.execute("TRUNCATE TABLE developer_scenario_steps");
                jdbcTemplate.execute("TRUNCATE TABLE developer_scenario_runs");
                jdbcTemplate.execute("TRUNCATE TABLE dispute_workflow_events");
                jdbcTemplate.execute("TRUNCATE TABLE dispute_outcomes");
                jdbcTemplate.execute("TRUNCATE TABLE dispute_evidence_versions");
                jdbcTemplate.execute("TRUNCATE TABLE dispute_workflows");
                jdbcTemplate.execute("TRUNCATE TABLE authored_rule_evaluations");
                jdbcTemplate.execute("TRUNCATE TABLE risk_safety_exception_events");
                jdbcTemplate.execute("TRUNCATE TABLE risk_safety_exception_versions");
                jdbcTemplate.execute("TRUNCATE TABLE authored_rule_events");
                jdbcTemplate.execute("TRUNCATE TABLE authored_rule_versions");
                jdbcTemplate.execute("TRUNCATE TABLE risk_simulation_segments");
                jdbcTemplate.execute("TRUNCATE TABLE risk_simulation_reports");
                jdbcTemplate.execute("TRUNCATE TABLE risk_simulation_results");
                jdbcTemplate.execute("TRUNCATE TABLE risk_simulation_events");
                jdbcTemplate.execute("TRUNCATE TABLE risk_simulation_runs");
                jdbcTemplate.execute("TRUNCATE TABLE batch_step_execution_context");
                jdbcTemplate.execute("TRUNCATE TABLE batch_step_execution");
                jdbcTemplate.execute("TRUNCATE TABLE batch_job_execution_context");
                jdbcTemplate.execute("TRUNCATE TABLE batch_job_execution_params");
                jdbcTemplate.execute("TRUNCATE TABLE batch_job_execution");
                jdbcTemplate.execute("TRUNCATE TABLE batch_job_instance");
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
                jdbcTemplate.execute("TRUNCATE TABLE merchant_risk_profile_recommendations");
                jdbcTemplate.execute("TRUNCATE TABLE merchant_risk_metric_snapshots");
            } finally {
                jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
        } else {
            jdbcTemplate.execute("TRUNCATE TABLE developer_scenario_events, developer_scenario_steps, "
                    + "developer_scenario_runs, dispute_workflow_events, dispute_outcomes, "
                    + "dispute_evidence_versions, dispute_workflows, "
                    + "authored_rule_evaluations, risk_safety_exception_events, "
                    + "risk_safety_exception_versions, authored_rule_events, authored_rule_versions, "
                    + "risk_simulation_segments, risk_simulation_reports, "
                    + "risk_simulation_results, risk_simulation_events, risk_simulation_runs, "
                    + "batch_step_execution_context, batch_step_execution, batch_job_execution_context, "
                    + "batch_job_execution_params, batch_job_execution, batch_job_instance, "
                    + "audit_logs, review_note_redaction_events, review_notes, "
                    + "review_saved_searches, merchant_risk_profile_recommendations, "
                    + "merchant_risk_metric_snapshots, webhook_endpoint_verification_events, "
                    + "webhook_endpoint_verifications, webhook_redelivery_requests, "
                    + "webhook_delivery_attempts, webhook_deliveries, "
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
