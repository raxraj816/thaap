package com.thaap.payments.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void flywayMigrationAndHibernateValidationWorkAgainstPostgreSql() {
        var applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1'
                  AND success = TRUE
                """, Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '8'
                  AND description = 'add refunds and refund ledger lineage'
                  AND success = TRUE
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '9'
                  AND description = 'harden webhook delivery execution'
                  AND success = TRUE
                """, Integer.class)).isEqualTo(1);
        assertThat(columnExists("payment_methods", "card_bin")).isTrue();
        assertThat(columnExists("payment_intents", "capture_method")).isTrue();
        assertThat(columnExists("payment_intents", "description")).isTrue();
        assertThat(columnExists("payment_intents", "metadata")).isTrue();
        assertThat(columnExists("payment_attempts", "payment_intent_id")).isTrue();
        assertThat(columnExists("payment_attempts", "payment_method_id")).isTrue();
        assertThat(columnExists("payment_attempts", "attempt_number")).isTrue();
        assertThat(columnExists("authorizations", "payment_attempt_id")).isTrue();
        assertThat(columnExists("authorizations", "requested_amount")).isTrue();
        assertThat(columnExists("authorizations", "authorized_amount")).isTrue();
        assertThat(columnExists("authorizations", "three_ds_result")).isTrue();
        assertThat(indexExists("authorizations", "uk_authorizations_attempt")).isTrue();
        assertThat(indexExists("authorizations", "uk_authorizations_processor_reference")).isTrue();
        assertThat(indexExists("authorizations", "idx_authorizations_merchant_created")).isTrue();
        assertThat(columnExists("authorization_voids", "authorization_id")).isTrue();
        assertThat(columnExists("authorization_voids", "payment_attempt_id")).isTrue();
        assertThat(columnExists("authorization_voids", "payment_intent_id")).isTrue();
        assertThat(columnExists("authorization_voids", "status")).isTrue();
        assertThat(columnExists("authorization_voids", "reason")).isTrue();
        assertThat(indexExists("authorization_voids", "uk_authorization_voids_authorization")).isTrue();
        assertThat(indexExists("authorization_voids", "uk_authorization_voids_payment_intent")).isTrue();
        assertThat(indexExists("authorization_voids", "idx_authorization_voids_merchant_created")).isTrue();
        assertThat(columnExists("fraud_decisions", "payment_attempt_id")).isTrue();
        assertThat(columnExists("fraud_decisions", "risk_score")).isTrue();
        assertThat(columnExists("fraud_decisions", "feature_freshness_status")).isTrue();
        assertThat(columnExists("fraud_decisions", "reason_codes")).isTrue();
        assertThat(columnExists("feature_snapshots", "fraud_decision_id")).isTrue();
        assertThat(columnExists("feature_snapshots", "feature_version")).isTrue();
        assertThat(columnExists("feature_snapshots", "features_json")).isTrue();
        assertThat(indexExists("fraud_decisions", "uk_fraud_decisions_attempt")).isTrue();
        assertThat(indexExists("feature_snapshots", "uk_feature_snapshots_decision")).isTrue();
        assertThat(columnExists("feature_definitions", "feature_code")).isTrue();
        assertThat(columnExists("feature_definitions", "value_type")).isTrue();
        assertThat(columnExists("feature_values", "feature_definition_id")).isTrue();
        assertThat(columnExists("feature_values", "observed_at")).isTrue();
        assertThat(indexExists("feature_values", "uk_feature_values_attempt_feature")).isTrue();
        assertThat(indexExists("feature_values", "idx_feature_values_point_in_time")).isTrue();
        assertThat(columnExists("audit_logs", "merchant_id")).isTrue();
        assertThat(columnExists("audit_logs", "action")).isTrue();
        assertThat(columnExists("audit_logs", "request_id")).isTrue();
        assertThat(columnExists("audit_logs", "after_json")).isTrue();
        assertThat(indexExists("audit_logs", "idx_audit_logs_merchant_created")).isTrue();
        assertThat(indexExists("audit_logs", "idx_audit_logs_merchant_aggregate")).isTrue();
        assertThat(columnExists("ledger_entries", "transaction_group_id")).isTrue();
        assertThat(columnExists("ledger_entries", "source_type")).isTrue();
        assertThat(columnExists("ledger_entries", "account")).isTrue();
        assertThat(columnExists("ledger_entries", "direction")).isTrue();
        assertThat(columnExists("ledger_entries", "payment_intent_id")).isTrue();
        assertThat(columnExists("ledger_entries", "refund_id")).isTrue();
        assertThat(indexExists("ledger_entries", "idx_ledger_entries_merchant_created")).isTrue();
        assertThat(indexExists("ledger_entries", "idx_ledger_entries_merchant_group")).isTrue();
        assertThat(indexExists("ledger_entries", "idx_ledger_entries_merchant_source")).isTrue();
        assertThat(indexExists("ledger_entries", "uk_ledger_entries_source_account")).isTrue();
        assertThat(indexExists("ledger_entries", "idx_ledger_entries_merchant_payment_intent")).isTrue();
        assertThat(indexExists("ledger_entries", "idx_ledger_entries_merchant_refund")).isTrue();
        assertThat(columnExists("refunds", "payment_intent_id")).isTrue();
        assertThat(columnExists("refunds", "payment_attempt_id")).isTrue();
        assertThat(columnExists("refunds", "authorization_id")).isTrue();
        assertThat(columnExists("refunds", "reason_catalog_version")).isTrue();
        assertThat(columnExists("refunds", "reason_code")).isTrue();
        assertThat(columnExists("refunds", "source_review_resolution_id")).isTrue();
        assertThat(indexExists("refunds", "uk_refunds_payment_intent")).isTrue();
        assertThat(columnExists("post_authorization_signals", "external_event_key")).isTrue();
        assertThat(columnExists("post_authorization_signals", "recommendation")).isTrue();
        assertThat(columnExists("review_refund_actions", "review_resolution_id")).isTrue();
        assertThat(columnExists("manual_review_cases", "source_post_authorization_signal_id")).isTrue();
        assertThat(columnExists("fraud_labels", "source_post_authorization_signal_id")).isTrue();
        assertThat(columnExists("card_testing_incidents", "source_attestation")).isTrue();
        assertThat(columnExists("incident_evidence_links", "payment_attempt_id")).isTrue();
        assertThat(columnExists("card_testing_incident_resolutions", "mitigation_code")).isTrue();
        assertThat(columnExists("chargebacks", "external_event_key")).isTrue();
        assertThat(columnExists("chargebacks", "allocated_amount")).isTrue();
        assertThat(columnExists("dispute_offset_allocations", "chargeback_id")).isTrue();
        assertThat(columnExists("dispute_evidence_packets", "authorization_id")).isTrue();
        assertThat(columnExists("fraud_labels", "source_chargeback_id")).isTrue();
        assertThat(columnExists("ledger_entries", "chargeback_id")).isTrue();
        assertThat(columnExists("idempotency_records", "operation")).isTrue();
        assertThat(columnExists("idempotency_records", "expires_at")).isTrue();
        assertThat(columnExists("idempotency_records", "lease_token")).isTrue();
        assertThat(columnExists("idempotency_records", "lease_expires_at")).isTrue();
        assertThat(columnExists("idempotency_records", "request_hash_key_version")).isTrue();
        assertThat(columnExists("merchant_creation_records", "idempotency_key_hash")).isTrue();
        assertThat(columnExists("merchant_creation_records", "merchant_id")).isTrue();
        assertThat(columnExists("merchant_creation_records", "derivation_key_version")).isTrue();
        assertThat(columnExists("merchant_creation_identities", "identity_hash")).isTrue();
        assertThat(columnExists("merchant_creation_identities", "key_version")).isTrue();
        assertThat(columnExists(
                "webhook_endpoint_creation_records",
                "request_hash_key_version")).isTrue();
        assertThat(columnExists(
                "webhook_endpoint_creation_identities", "identity_hash")).isTrue();
        assertThat(columnExists(
                "webhook_endpoint_creation_identities", "key_version")).isTrue();
        assertThat(columnExists("webhook_deliveries", "attempt_count")).isTrue();
        assertThat(columnExists("webhook_deliveries", "webhook_event_id")).isTrue();
        assertThat(columnExists("webhook_deliveries", "claim_token")).isTrue();
        assertThat(columnExists("webhook_events", "payload_sha256")).isTrue();
        assertThat(columnExists("webhook_delivery_attempts", "attempt_number")).isTrue();
        assertThat(columnExists("webhook_endpoints", "operational_status")).isTrue();
    }

    @Test
    void authorizationVoidUpgradePreservesLegacyOpenAutomaticPaymentSemanticsOnPostgreSql() {
        String schema = "auth_void_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        Flyway upgradeFlyway = Flyway.configure()
                .dataSource(upgradeDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("5")
                .load();

        try {
            upgradeFlyway.migrate();
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            String qualifiedMerchants = schema + ".merchants";
            String qualifiedCustomers = schema + ".customers";
            String qualifiedPaymentIntents = schema + ".payment_intents";
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s (id, name, status, api_key_prefix, api_key_hash, created_at)
                    VALUES (?, 'legacy-postgres-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(qualifiedMerchants), merchantId, "legacy-" + schema);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s (id, merchant_id, email_hash, created_at)
                    VALUES (?, ?, 'legacy-postgres-email-hash', CURRENT_TIMESTAMP)
                    """.formatted(qualifiedCustomers), customerId, merchantId);

            List<String> openStatuses = List.of(
                    "REQUIRES_PAYMENT_METHOD",
                    "REQUIRES_CONFIRMATION",
                    "REQUIRES_FRAUD_CHECK",
                    "REQUIRES_ACTION",
                    "PROCESSING",
                    "AUTHORIZED",
                    "REQUIRES_CAPTURE");
            List<String> terminalStatuses = List.of("CAPTURED", "CANCELED", "REFUNDED", "DISPUTED", "FAILED");
            Map<String, UUID> paymentIntentIdsByStatus = new LinkedHashMap<>();
            for (String status : Stream.concat(openStatuses.stream(), terminalStatuses.stream()).toList()) {
                UUID paymentIntentId = UUID.randomUUID();
                paymentIntentIdsByStatus.put(status, paymentIntentId);
                upgradeJdbcTemplate.update("""
                        INSERT INTO %s (
                            id, merchant_id, customer_id, amount, currency, capture_method, status,
                            metadata, created_at, updated_at
                        )
                        VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', ?, '{}',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """.formatted(qualifiedPaymentIntents),
                        paymentIntentId, merchantId, customerId, status);
            }

            Flyway.configure()
                    .dataSource(upgradeDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("7")
                    .load()
                    .migrate();

            for (String status : openStatuses) {
                assertThat(captureMethod(upgradeJdbcTemplate, qualifiedPaymentIntents,
                        paymentIntentIdsByStatus.get(status)))
                        .as("legacy PostgreSQL open status %s", status)
                        .isEqualTo("MANUAL");
            }
            for (String status : terminalStatuses) {
                assertThat(captureMethod(upgradeJdbcTemplate, qualifiedPaymentIntents,
                        paymentIntentIdsByStatus.get(status)))
                        .as("legacy PostgreSQL terminal status %s", status)
                        .isEqualTo("AUTOMATIC");
            }
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void refundLedgerUpgradeBackfillsCaptureLedgerPaymentLineageOnPostgreSql() {
        String schema = "refund_ledger_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "7");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID paymentIntentId = UUID.randomUUID();
            insertMerchantCustomerAndPaymentIntent(upgradeJdbcTemplate, schema, merchantId, customerId,
                    paymentIntentId, "CAPTURED");
            insertLegacyCaptureLedgerEntry(upgradeJdbcTemplate, schema, merchantId, paymentIntentId,
                    "CUSTOMER_RECEIVABLE", "DEBIT");
            insertLegacyCaptureLedgerEntry(upgradeJdbcTemplate, schema, merchantId, paymentIntentId,
                    "MERCHANT_PAYABLE", "CREDIT");

            migrateSchema(upgradeDataSource, schema, "8");

            List<Map<String, Object>> entries = upgradeJdbcTemplate.queryForList("""
                    SELECT source_id, payment_intent_id, refund_id
                    FROM %s.ledger_entries
                    ORDER BY account
                    """.formatted(schema));
            assertThat(entries).hasSize(2);
            assertThat(entries).allSatisfy(entry -> {
                assertThat(entry.get("source_id")).isEqualTo(paymentIntentId);
                assertThat(entry.get("payment_intent_id")).isEqualTo(paymentIntentId);
                assertThat(entry.get("refund_id")).isNull();
            });
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void refundLedgerUpgradeRejectsLegacyRefundedPaymentIntentsOnPostgreSql() {
        String schema = "refund_ledger_guard_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "7");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID paymentIntentId = UUID.randomUUID();
            insertMerchantCustomerAndPaymentIntent(upgradeJdbcTemplate, schema, merchantId, customerId,
                    paymentIntentId, "REFUNDED");

            assertThatThrownBy(() -> migrateSchema(upgradeDataSource, schema, "8"))
                    .hasMessageContaining("V8__add_refunds_and_refund_ledger_lineage.sql")
                    .hasStackTraceContaining("ck_chapter18_no_legacy_refunded_payment_intents");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v9MigrationBackfillsLegacyWebhookTruthOnPostgreSql() {
        String schema = "v9_webhook_backfill_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "8");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID endpointId = UUID.randomUUID();
            UUID disabledEndpointId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            UUID disabledDeliveryId = UUID.randomUUID();
            UUID resourceId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    )
                    VALUES (?, 'legacy-webhook', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "legacy-webhook-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints (
                        id, merchant_id, url, active, signing_secret_prefix,
                        signing_secret_hash, created_at
                    )
                    VALUES (?, ?, 'https://legacy.example/webhooks', TRUE, 'whsec_', ?,
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), endpointId, merchantId, "legacy-secret-" + endpointId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints (
                        id, merchant_id, url, active, signing_secret_prefix,
                        signing_secret_hash, created_at
                    )
                    VALUES (?, ?, 'https://disabled.example/webhooks', FALSE, 'whsec_', ?,
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), disabledEndpointId, merchantId,
                    "legacy-secret-" + disabledEndpointId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_deliveries (
                        id, merchant_id, webhook_endpoint_id, event_type, resource_id,
                        status, attempt_count, created_at
                    )
                    VALUES (?, ?, ?, 'refund.created', ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                    """.formatted(schema), deliveryId, merchantId, endpointId, resourceId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_deliveries (
                        id, merchant_id, webhook_endpoint_id, event_type, resource_id,
                        status, attempt_count, created_at
                    )
                    VALUES (?, ?, ?, 'refund.created', ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                    """.formatted(schema), disabledDeliveryId, merchantId,
                    disabledEndpointId, UUID.randomUUID());

            migrateSchema(upgradeDataSource, schema, "9");

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT operational_status
                    FROM %s.webhook_endpoints
                    WHERE id = ?
                    """.formatted(schema), String.class, endpointId))
                    .isEqualTo("REQUIRES_SECRET_ROTATION");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT webhook_event_id
                    FROM %s.webhook_deliveries
                    WHERE id = ?
                    """.formatted(schema), UUID.class, deliveryId)).isEqualTo(deliveryId);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM %s.webhook_deliveries
                    WHERE webhook_event_id = id
                    """.formatted(schema), Integer.class)).isEqualTo(2);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM %s.webhook_events
                    """.formatted(schema), Integer.class)).isEqualTo(2);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM %s.webhook_delivery_attempts
                    """.formatted(schema), Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ?
                    """.formatted(schema), Integer.class, endpointId)).isEqualTo(5);
            Map<String, Object> queued = upgradeJdbcTemplate.queryForMap("""
                    SELECT status, next_attempt_at, last_error_code
                    FROM %s.webhook_deliveries
                    WHERE id = ?
                    """.formatted(schema), deliveryId);
            assertThat(queued)
                    .containsEntry("status", "PENDING")
                    .containsEntry("next_attempt_at", null)
                    .containsEntry("last_error_code", null);
            Map<String, Object> disabled = upgradeJdbcTemplate.queryForMap("""
                    SELECT status, next_attempt_at, delivered_at, last_error_code
                    FROM %s.webhook_deliveries
                    WHERE id = ?
                    """.formatted(schema), disabledDeliveryId);
            assertThat(disabled)
                    .containsEntry("status", "EXHAUSTED")
                    .containsEntry("last_error_code", "ENDPOINT_DISABLED");
            assertThat(disabled.get("next_attempt_at")).isNull();
            assertThat(disabled.get("delivered_at")).isNull();

            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_delivery_attempts (
                        id, merchant_id, webhook_delivery_id, attempt_number, outcome,
                        http_request_sent, started_at
                    )
                    VALUES (?, ?, ?, 1, 'IN_PROGRESS', NULL, CURRENT_TIMESTAMP)
                    """.formatted(schema), UUID.randomUUID(), merchantId, deliveryId))
                    .hasMessageContaining("ck_webhook_delivery_attempts_dispatch_truth");

            UUID attemptId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_delivery_attempts (
                        id, merchant_id, webhook_delivery_id, attempt_number, outcome,
                        http_request_sent, started_at
                    )
                    VALUES (?, ?, ?, 1, 'IN_PROGRESS', FALSE, CURRENT_TIMESTAMP)
                    """.formatted(schema), attemptId, merchantId, deliveryId);
            upgradeJdbcTemplate.update("""
                    UPDATE %s.webhook_delivery_attempts
                    SET dispatch_started_at = CURRENT_TIMESTAMP,
                        http_request_sent = NULL
                    WHERE id = ?
                    """.formatted(schema), attemptId);
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.webhook_delivery_attempts
                    SET http_request_sent = FALSE
                    WHERE id = ?
                    """.formatted(schema), attemptId))
                    .hasMessageContaining("ck_webhook_delivery_attempts_dispatch_truth");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v9MigrationRejectsLegacyExecutionStateWithoutAttemptEvidenceOnPostgreSql() {
        String schema = "v9_webhook_execution_guard_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "8");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID endpointId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    )
                    VALUES (?, 'legacy-webhook-guard', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "legacy-webhook-guard-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints (
                        id, merchant_id, url, active, signing_secret_prefix,
                        signing_secret_hash, created_at
                    )
                    VALUES (?, ?, 'https://legacy.example/webhooks', TRUE, 'whsec_', ?,
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), endpointId, merchantId, "legacy-secret-" + endpointId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_deliveries (
                        id, merchant_id, webhook_endpoint_id, event_type, resource_id,
                        status, attempt_count, next_attempt_at, created_at
                    )
                    VALUES
                        (?, ?, ?, 'refund.created', ?, 'PENDING', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (?, ?, ?, 'refund.created', ?, 'SUCCEEDED', 1, NULL, CURRENT_TIMESTAMP),
                        (?, ?, ?, 'refund.created', ?, 'FAILED', 2, NULL, CURRENT_TIMESTAMP)
                    """.formatted(schema),
                    UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID(),
                    UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID(),
                    UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID());

            assertThatThrownBy(() -> migrateSchema(upgradeDataSource, schema, "9"))
                    .hasStackTraceContaining(
                            "V9 cannot migrate unsupported legacy webhook delivery execution state")
                    .hasStackTraceContaining("3 row(s)");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v9MigrationRejectsUnsupportedLegacyEventTypesBeforeDdlOnPostgreSql() {
        String schema = "v9_webhook_event_guard_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "8");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID endpointId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    )
                    VALUES (?, 'legacy-event-guard', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "legacy-event-guard-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints (
                        id, merchant_id, url, active, signing_secret_prefix,
                        signing_secret_hash, created_at
                    )
                    VALUES (?, ?, 'https://legacy.example/webhooks', TRUE, 'whsec_', ?,
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), endpointId, merchantId, "legacy-secret-" + endpointId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_deliveries (
                        id, merchant_id, webhook_endpoint_id, event_type, resource_id,
                        status, attempt_count, created_at
                    )
                    VALUES (?, ?, ?, 'legacy.unknown', ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                    """.formatted(schema), UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID());

            assertThatThrownBy(() -> migrateSchema(upgradeDataSource, schema, "9"))
                    .hasStackTraceContaining(
                            "V9 cannot migrate unsupported legacy webhook event types")
                    .hasStackTraceContaining("1 row(s)");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = ?
                      AND table_name = 'webhook_events'
                    """, Integer.class, schema)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'webhook_endpoints'
                      AND column_name = 'operational_status'
                    """, Integer.class, schema)).isZero();
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v13RepairsPreviouslyAppliedWebhookAttemptSchemaWithoutLosingSendTruth() {
        String schema = "v13_attempt_repair_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "12");

        try {
            upgradeJdbcTemplate.execute("""
                    ALTER TABLE %s.webhook_delivery_attempts
                    DROP CONSTRAINT ck_webhook_delivery_attempts_dispatch_truth
                    """.formatted(schema));
            upgradeJdbcTemplate.execute("""
                    ALTER TABLE %s.webhook_delivery_attempts
                    DROP COLUMN dispatch_started_at
                    """.formatted(schema));
            upgradeJdbcTemplate.execute("""
                    ALTER TABLE %s.webhook_delivery_attempts
                    ALTER COLUMN http_request_sent SET NOT NULL
                    """.formatted(schema));
            UUID merchantId = UUID.randomUUID();
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID resourceId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    ) VALUES (?, 'v13-repair', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "v13-repair-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints (
                        id, merchant_id, url, operational_status,
                        signing_secret_prefix, signing_secret_hash,
                        signing_secret_ciphertext, signing_secret_nonce,
                        signing_secret_key_version, signing_secret_generation,
                        created_at
                    ) VALUES (
                        ?, ?, 'https://repair.example/webhook', 'ACTIVE',
                        'whsec_', ?, 'ciphertext', 'nonce', 'v1', ?,
                        CURRENT_TIMESTAMP
                    )
                    """.formatted(schema), endpointId, merchantId,
                    "v13-secret-" + endpointId, UUID.randomUUID());
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_events (
                        id, merchant_id, event_type, resource_id, resource_type,
                        api_version, payload, payload_sha256, occurred_at,
                        created_at, traffic_origin
                    ) VALUES (
                        ?, ?, 'refund.created', ?, 'REFUND', 'v1', '{}',
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MERCHANT_API'
                    )
                    """.formatted(schema), eventId, merchantId, resourceId, "0".repeat(64));
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_deliveries (
                        id, merchant_id, webhook_endpoint_id, webhook_event_id,
                        event_type, resource_id, status, attempt_count,
                        delivered_at, created_at
                    ) VALUES (
                        ?, ?, ?, ?, 'refund.created', ?, 'SUCCEEDED', 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """.formatted(schema), deliveryId, merchantId, endpointId,
                    eventId, resourceId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_delivery_attempts (
                        id, merchant_id, webhook_delivery_id, attempt_number,
                        outcome, http_request_sent, response_status, started_at,
                        completed_at, latency_ms
                    ) VALUES (
                        ?, ?, ?, 1, 'SUCCEEDED', TRUE, 204,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                    )
                    """.formatted(schema), attemptId, merchantId, deliveryId);

            migrateSchema(upgradeDataSource, schema, "13");

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT dispatch_started_at IS NOT NULL
                    FROM %s.webhook_delivery_attempts
                    WHERE id = ?
                    """.formatted(schema), Boolean.class, attemptId)).isTrue();
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'webhook_delivery_attempts'
                      AND column_name = 'http_request_sent'
                    """, String.class, schema)).isEqualTo("YES");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v14MakesPreviouslyStrandedEndpointCreationReservationsReclaimable() {
        String schema = "v14_creation_lease_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "13");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID recordId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    ) VALUES (?, 'v14-creation-lease', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "v14-creation-lease-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_creation_records (
                        id, merchant_id, idempotency_key_hash, request_hash, status,
                        created_at, updated_at, expires_at
                    ) VALUES (
                        ?, ?, ?, ?, 'IN_PROGRESS',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '24 hours'
                    )
                    """.formatted(schema), recordId, merchantId,
                    "1".repeat(64), "2".repeat(64));

            migrateSchema(upgradeDataSource, schema, "14");

            Map<String, Object> leased = upgradeJdbcTemplate.queryForMap("""
                    SELECT lease_token, lease_expires_at
                    FROM %s.webhook_endpoint_creation_records
                    WHERE id = ?
                    """.formatted(schema), recordId);
            assertThat(leased.get("lease_token")).isEqualTo(recordId);
            assertThat(leased.get("lease_expires_at")).isNotNull();
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.webhook_endpoint_creation_records
                    SET lease_token = NULL,
                        lease_expires_at = NULL
                    WHERE id = ?
                    """.formatted(schema), recordId))
                    .hasMessageContaining("ck_webhook_endpoint_creation_lease");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v15LeasesStrandedIdempotencyAndCreatesSecretSafeMerchantReservations() {
        String schema = "v15_creation_idempotency_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "14");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID recordId = UUID.randomUUID();
            UUID completedRecordId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    ) VALUES (?, 'v15-idempotency', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "v15-idempotency-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.idempotency_records (
                        id, merchant_id, idempotency_key, operation, request_hash,
                        status, created_at, updated_at, expires_at
                    ) VALUES (
                        ?, ?, 'stranded-key', 'customers.create', ?, 'IN_PROGRESS',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '24 hours'
                    )
                    """.formatted(schema), recordId, merchantId, "1".repeat(64));
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.idempotency_records (
                        id, merchant_id, idempotency_key, operation, request_hash,
                        status, http_status, response_body,
                        created_at, updated_at, expires_at
                    ) VALUES (
                        ?, ?, 'completed-key', 'customers.create', ?,
                        'COMPLETED', 201, '{"id":"safe-id"}',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP + INTERVAL '24 hours'
                    )
                    """.formatted(schema), completedRecordId, merchantId, "2".repeat(64));

            migrateSchema(upgradeDataSource, schema, "15");

            Map<String, Object> leased = upgradeJdbcTemplate.queryForMap("""
                    SELECT lease_token, lease_expires_at
                    FROM %s.idempotency_records
                    WHERE id = ?
                    """.formatted(schema), recordId);
            assertThat(leased.get("lease_token")).isEqualTo(recordId);
            assertThat(leased.get("lease_expires_at")).isNotNull();
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT lease_expires_at <= CURRENT_TIMESTAMP
                    FROM %s.idempotency_records
                    WHERE id = ?
                    """.formatted(schema), Boolean.class, recordId)).isTrue();
            Map<String, Object> completed = upgradeJdbcTemplate.queryForMap("""
                    SELECT lease_token, lease_expires_at, response_body
                    FROM %s.idempotency_records
                    WHERE id = ?
                    """.formatted(schema), completedRecordId);
            assertThat(completed.get("lease_token")).isNull();
            assertThat(completed.get("lease_expires_at")).isNull();
            assertThat(completed.get("response_body")).isEqualTo("{\"id\":\"safe-id\"}");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = ?
                      AND table_name = 'merchant_creation_records'
                    """, Integer.class, schema)).isEqualTo(1);
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.idempotency_records
                    SET lease_token = NULL,
                        lease_expires_at = NULL
                    WHERE id = ?
                    """.formatted(schema), recordId))
                    .hasMessageContaining("ck_idempotency_records_lease");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.idempotency_records
                    SET lease_token = ?,
                        lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute'
                    WHERE id = ?
                    """.formatted(schema), UUID.randomUUID(), completedRecordId))
                    .hasMessageContaining("ck_idempotency_records_lease");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v16BackfillsVersionedMerchantCreationIdentityWithoutLosingReplayLineage() {
        String schema = "v16_merchant_identity_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "15");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID recordId = UUID.randomUUID();
            String identityHash = "a".repeat(64);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    ) VALUES (?, 'v16-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "v16-merchant-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchant_creation_records (
                        id, idempotency_key_hash, request_hash, status, merchant_id,
                        created_at, updated_at, expires_at
                    ) VALUES (
                        ?, ?, ?, 'COMPLETED', ?,
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '24 hours'
                    )
                    """.formatted(schema), recordId, identityHash,
                    "b".repeat(64), merchantId);

            migrateSchema(upgradeDataSource, schema, "16");

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT derivation_key_version
                    FROM %s.merchant_creation_records
                    WHERE id = ?
                    """.formatted(schema), String.class, recordId)).isEqualTo("v1");
            Map<String, Object> identity = upgradeJdbcTemplate.queryForMap("""
                    SELECT identity_hash, key_version, merchant_creation_record_id
                    FROM %s.merchant_creation_identities
                    WHERE identity_hash = ?
                    """.formatted(schema), identityHash);
            assertThat(identity.get("identity_hash")).isEqualTo(identityHash);
            assertThat(identity.get("key_version")).isEqualTo("v1");
            assertThat(identity.get("merchant_creation_record_id")).isEqualTo(recordId);
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchant_creation_identities (
                        identity_hash, key_version, merchant_creation_record_id, created_at
                    ) VALUES (?, 'v2', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), identityHash, recordId))
                    .hasMessageContaining("pk_merchant_creation_identities");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v17BackfillsEveryLiveReplayHashVersionAndWebhookIdentityAlias() {
        String schema = "v17_replay_identity_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "16");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID idempotencyRecordId = UUID.randomUUID();
            UUID webhookCreationRecordId = UUID.randomUUID();
            String webhookIdentityHash = "c".repeat(64);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at
                    ) VALUES (?, 'v17-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "v17-merchant-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.idempotency_records (
                        id, merchant_id, idempotency_key, operation, request_hash,
                        status, lease_token, lease_expires_at,
                        created_at, updated_at, expires_at
                    ) VALUES (
                        ?, ?, 'legacy-key', 'customers.create', ?,
                        'IN_PROGRESS', ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '24 hours'
                    )
                    """.formatted(schema), idempotencyRecordId, merchantId,
                    "a".repeat(64), UUID.randomUUID());
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_creation_records (
                        id, merchant_id, idempotency_key_hash, request_hash,
                        status, lease_token, lease_expires_at,
                        created_at, updated_at, expires_at
                    ) VALUES (
                        ?, ?, ?, ?,
                        'IN_PROGRESS', ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '24 hours'
                    )
                    """.formatted(schema), webhookCreationRecordId, merchantId,
                    webhookIdentityHash, "b".repeat(64), UUID.randomUUID());

            migrateSchema(upgradeDataSource, schema, "17");

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT request_hash_key_version
                    FROM %s.idempotency_records
                    WHERE id = ?
                    """.formatted(schema), String.class, idempotencyRecordId))
                    .isEqualTo("v1");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT request_hash_key_version
                    FROM %s.webhook_endpoint_creation_records
                    WHERE id = ?
                    """.formatted(schema), String.class, webhookCreationRecordId))
                    .isEqualTo("v1");
            Map<String, Object> alias = upgradeJdbcTemplate.queryForMap("""
                    SELECT merchant_id, identity_hash, key_version, creation_record_id
                    FROM %s.webhook_endpoint_creation_identities
                    WHERE creation_record_id = ?
                    """.formatted(schema), webhookCreationRecordId);
            assertThat(alias.get("merchant_id")).isEqualTo(merchantId);
            assertThat(alias.get("identity_hash")).isEqualTo(webhookIdentityHash);
            assertThat(alias.get("key_version")).isEqualTo("v1");
            assertThat(alias.get("creation_record_id"))
                    .isEqualTo(webhookCreationRecordId);

            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_creation_identities (
                        id, merchant_id, identity_hash, key_version,
                        creation_record_id, created_at
                    ) VALUES (?, ?, ?, 'v2', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), UUID.randomUUID(), merchantId,
                    webhookIdentityHash, webhookCreationRecordId))
                    .hasMessageContaining(
                            "uk_webhook_endpoint_creation_identity_merchant_hash");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void fraudLineageUpgradePreservesHistoricalTruthAndBindsFraudActionLineage() {
        String schema = "fraud_lineage_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "17");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID paymentMethodId = UUID.randomUUID();
            UUID paymentIntentId = UUID.randomUUID();
            UUID paymentAttemptId = UUID.randomUUID();
            UUID decisionId = UUID.randomUUID();
            UUID snapshotId = UUID.randomUUID();
            String historicalSnapshot = "{\"card_bin\":\"499999\",\"payment_amount\":1200}";
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                    VALUES (?, 'fraud-lineage-upgrade', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "fraud-lineage-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.customers (id, merchant_id, email_hash, created_at)
                    VALUES (?, ?, 'fraud-lineage-email', CURRENT_TIMESTAMP)
                    """.formatted(schema), customerId, merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.payment_methods
                        (id, merchant_id, customer_id, type, token, card_fingerprint, card_bin, card_last4,
                         card_funding, card_country, created_at)
                    VALUES (?, ?, ?, 'CARD', 'tok-old', 'fp-old', '499999', '0001', 'CREDIT', 'US',
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), paymentMethodId, merchantId, customerId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.payment_intents
                        (id, merchant_id, customer_id, amount, currency, capture_method, status, metadata,
                         traffic_origin, created_at, updated_at)
                    VALUES (?, ?, ?, 1200, 'USD', 'MANUAL', 'PROCESSING', '{}', 'MERCHANT_API',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(schema), paymentIntentId, merchantId, customerId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.payment_attempts
                        (id, merchant_id, payment_intent_id, payment_method_id, idempotency_key, status,
                         attempt_number, fraud_decision_id, traffic_origin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'historical-confirm', 'READY_FOR_PROCESSOR', 1, ?, 'MERCHANT_API',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(schema), paymentAttemptId, merchantId, paymentIntentId, paymentMethodId, decisionId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.fraud_decisions
                        (id, merchant_id, payment_attempt_id, risk_score, risk_level, action, model_version,
                         ruleset_version, latency_ms, decision_started_at, decision_completed_at,
                         feature_freshness_status, max_feature_age_ms, reason_codes, traffic_origin, created_at)
                    VALUES (?, ?, ?, 0.0000, 'LOW', 'ALLOW', NULL, 'rules_stub_v1', 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'FRESH', 0,
                            '["RULES_NOT_ENABLED"]', 'MERCHANT_API', CURRENT_TIMESTAMP)
                    """.formatted(schema), decisionId, merchantId, paymentAttemptId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.feature_snapshots
                        (id, merchant_id, fraud_decision_id, payment_attempt_id, feature_version, features_json,
                         computed_at, oldest_feature_timestamp, max_feature_age_ms, freshness_status,
                         stale_feature_names, missing_feature_names, traffic_origin, created_at)
                    VALUES (?, ?, ?, ?, 'features_v1', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 'FRESH',
                            '[]', '[]', 'MERCHANT_API', CURRENT_TIMESTAMP)
                    """.formatted(schema), snapshotId, merchantId, decisionId, paymentAttemptId, historicalSnapshot);
            UUID legacyRuleId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.fraud_rules (id, merchant_id, name, created_at)
                    VALUES (?, ?, 'legacy-placeholder-rule', CURRENT_TIMESTAMP)
                    """.formatted(schema), legacyRuleId, merchantId);

            migrateSchema(upgradeDataSource, schema, "19");

            UUID subsetWebhookEndpointId = UUID.randomUUID();
            UUID fullSetWebhookEndpointId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints
                        (id, merchant_id, url, operational_status, signing_secret_prefix,
                         signing_secret_hash, signing_secret_generation, created_at)
                    VALUES (?, ?, 'https://subset.example/webhooks', 'DISABLED', 'whsec_', ?, ?,
                            CURRENT_TIMESTAMP - INTERVAL '2 seconds')
                    """.formatted(schema), subsetWebhookEndpointId, merchantId,
                    "subset-webhook-" + subsetWebhookEndpointId, UUID.randomUUID());
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_event_subscriptions
                        (merchant_id, webhook_endpoint_id, event_type, created_at)
                    VALUES (?, ?, 'payment_intent.created', CURRENT_TIMESTAMP - INTERVAL '1 second')
                    """.formatted(schema), merchantId, subsetWebhookEndpointId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints
                        (id, merchant_id, url, operational_status, signing_secret_prefix,
                         signing_secret_hash, signing_secret_generation, created_at)
                    VALUES (?, ?, 'https://full-set.example/webhooks', 'DISABLED', 'whsec_', ?, ?,
                            CURRENT_TIMESTAMP - INTERVAL '2 seconds')
                    """.formatted(schema), fullSetWebhookEndpointId, merchantId,
                    "full-set-webhook-" + fullSetWebhookEndpointId, UUID.randomUUID());
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_event_subscriptions
                        (merchant_id, webhook_endpoint_id, event_type, created_at)
                    SELECT ?, ?, event.event_type, CURRENT_TIMESTAMP - INTERVAL '1 second'
                    FROM (VALUES
                        ('payment_intent.created'),
                        ('payment_attempt.created'),
                        ('payment_intent.captured'),
                        ('payment_intent.canceled'),
                        ('refund.created')
                    ) AS event(event_type)
                    """.formatted(schema), merchantId, fullSetWebhookEndpointId);

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT checksum FROM %s.flyway_schema_history WHERE version = '18'
                    """.formatted(schema), Integer.class)).isEqualTo(1512291140);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT ruleset_version FROM %s.fraud_decisions WHERE id = ?
                    """.formatted(schema), String.class, decisionId)).isEqualTo("rules_stub_v1");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT features_json FROM %s.feature_snapshots WHERE id = ?
                    """.formatted(schema), String.class, snapshotId)).isEqualTo(historicalSnapshot);

            migrateSchema(upgradeDataSource, schema, "24");

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ?
                    """.formatted(schema), Integer.class, fullSetWebhookEndpointId)).isEqualTo(9);

            UUID postPhaseThreeWebhookEndpointId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints
                        (id, merchant_id, url, operational_status, signing_secret_prefix,
                         signing_secret_hash, signing_secret_generation, created_at)
                    VALUES (?, ?, 'https://post-phase-three.example/webhooks', 'DISABLED', 'whsec_', ?, ?,
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), postPhaseThreeWebhookEndpointId, merchantId,
                    "post-phase-three-webhook-" + postPhaseThreeWebhookEndpointId, UUID.randomUUID());
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_event_subscriptions
                        (merchant_id, webhook_endpoint_id, event_type, created_at)
                    SELECT ?, ?, event.event_type, endpoint.created_at
                    FROM %s.webhook_endpoints endpoint
                    CROSS JOIN (VALUES
                        ('payment_intent.created'),
                        ('payment_attempt.created'),
                        ('payment_intent.captured'),
                        ('payment_intent.canceled'),
                        ('payment_intent.blocked'),
                        ('payment_intent.requires_action'),
                        ('fraud_review.opened'),
                        ('fraud_review.canceled'),
                        ('refund.created')
                    ) AS event(event_type)
                    WHERE endpoint.id = ?
                    """.formatted(schema, schema), merchantId, postPhaseThreeWebhookEndpointId,
                    postPhaseThreeWebhookEndpointId);

            migrateSchema(upgradeDataSource, schema, "25");

            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT ruleset_version FROM %s.fraud_decisions WHERE id = ?
                    """.formatted(schema), String.class, decisionId)).isEqualTo("rules_stub_v1");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT features_json FROM %s.feature_snapshots WHERE id = ?
                    """.formatted(schema), String.class, snapshotId)).isEqualTo(historicalSnapshot);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.feature_values
                    """.formatted(schema), Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.feature_definitions WHERE feature_version = 'v1'
                    """.formatted(schema), Integer.class)).isEqualTo(7);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.fraud_rules WHERE ruleset_version = 'rules_v1'
                    """.formatted(schema), Integer.class)).isEqualTo(3);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT name FROM %s.legacy_fraud_rules WHERE id = ?
                    """.formatted(schema), String.class, legacyRuleId)).isEqualTo("legacy-placeholder-rule");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.rule_execution_results
                    """.formatted(schema), Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ?
                    """.formatted(schema), Integer.class, subsetWebhookEndpointId)).isEqualTo(1);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ?
                    """.formatted(schema), Integer.class, fullSetWebhookEndpointId)).isEqualTo(5);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ?
                    """.formatted(schema), Integer.class, postPhaseThreeWebhookEndpointId)).isEqualTo(9);
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.feature_definitions SET value_type = 'STRING'
                    WHERE feature_code = 'payment.amount_minor'
                    """.formatted(schema))).hasMessageContaining("feature lineage is immutable");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    INSERT INTO %s.feature_definitions
                        (id, feature_code, entity_type, value_type, owner, feature_version,
                         description, created_at)
                    VALUES (?, 'unsafe.extra', 'PAYMENT_ATTEMPT', 'STRING', 'TEST', 'v1', 'unsafe',
                            CURRENT_TIMESTAMP)
                    """.formatted(schema), UUID.randomUUID())).hasMessageContaining("feature lineage is immutable");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.fraud_rules SET long_threshold = 1
                    WHERE rule_code = 'BLOCK_USD_EXTREME_AMOUNT_V1'
                    """.formatted(schema))).hasMessageContaining("rule lineage is immutable");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    DELETE FROM %s.fraud_rules
                    WHERE rule_code = 'REVIEW_REPEATED_ATTEMPT_V1'
                    """.formatted(schema))).hasMessageContaining("rule lineage is immutable");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    INSERT INTO %s.rule_execution_results
                        (id, merchant_id, fraud_decision_id, rule_id, rule_code, rule_version,
                         priority, matched, action, reason_code, evaluation_outcome, latency_us,
                         traffic_origin, created_at)
                    VALUES (?, ?, ?, '23000000-0000-0000-0000-000000000001',
                            'BLOCK_USD_EXTREME_AMOUNT_V1', 'rules_v1', 100, FALSE, 'REVIEW',
                            'EXTREME_AMOUNT', 'NO_MATCH', 0, 'MERCHANT_API', CURRENT_TIMESTAMP)
                    """.formatted(schema), UUID.randomUUID(), merchantId, decisionId))
                    .hasMessageContaining("fk_rule_results_rule_semantics");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    INSERT INTO %s.manual_review_cases
                        (id, merchant_id, payment_intent_id, payment_attempt_id, fraud_decision_id,
                         decision_action, status, reason_codes, traffic_origin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'REVIEW', 'OPEN', '["RULES_NOT_ENABLED"]',
                            'MERCHANT_API', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(schema), UUID.randomUUID(), merchantId, paymentIntentId,
                    paymentAttemptId, decisionId))
                    .hasMessageContaining("fk_manual_reviews_decision_lineage");
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void reviewResolutionUpgradePreservesRetainedReviewsAndWebhookConsentWithoutTriggerBypass() {
        String schema = "review_resolution_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        migrateSchema(upgradeDataSource, schema, "25");

        try {
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID paymentMethodId = UUID.randomUUID();
            UUID webhookEndpointId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                    VALUES (?, 'review-resolution-upgrade', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "review-resolution-" + merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.customers (id, merchant_id, email_hash, created_at)
                    VALUES (?, ?, 'review-resolution-email', CURRENT_TIMESTAMP)
                    """.formatted(schema), customerId, merchantId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.payment_methods
                        (id, merchant_id, customer_id, type, token, card_fingerprint, card_bin, card_last4,
                         card_funding, card_country, created_at)
                    VALUES (?, ?, ?, 'CARD', 'tok-review-resolution', 'fp-review-resolution', '499999', '0003',
                            'CREDIT', 'US', CURRENT_TIMESTAMP)
                    """.formatted(schema), paymentMethodId, merchantId, customerId);

            for (String status : List.of("OPEN", "CANCELED")) {
                UUID paymentIntentId = UUID.randomUUID();
                UUID paymentAttemptId = UUID.randomUUID();
                UUID fraudDecisionId = UUID.randomUUID();
                UUID reviewCaseId = UUID.randomUUID();
                upgradeJdbcTemplate.update("""
                        INSERT INTO %s.payment_intents
                            (id, merchant_id, customer_id, amount, currency, capture_method, status, metadata,
                             traffic_origin, created_at, updated_at)
                        VALUES (?, ?, ?, 99999, 'USD', 'MANUAL', ?, '{}', 'MERCHANT_API',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """.formatted(schema), paymentIntentId, merchantId, customerId,
                        "OPEN".equals(status) ? "REQUIRES_REVIEW" : "CANCELED");
                upgradeJdbcTemplate.update("""
                        INSERT INTO %s.payment_attempts
                            (id, merchant_id, payment_intent_id, payment_method_id, idempotency_key, status,
                             attempt_number, fraud_decision_id, traffic_origin, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 3, ?, 'MERCHANT_API', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """.formatted(schema), paymentAttemptId, merchantId, paymentIntentId, paymentMethodId,
                        "review-resolution-" + status.toLowerCase(),
                        "OPEN".equals(status) ? "IN_REVIEW" : "CANCELED", fraudDecisionId);
                upgradeJdbcTemplate.update("""
                        INSERT INTO %s.fraud_decisions
                            (id, merchant_id, payment_attempt_id, risk_score, risk_level, action,
                             ruleset_version, latency_ms, decision_started_at, decision_completed_at,
                             feature_freshness_status, max_feature_age_ms, reason_codes,
                             traffic_origin, created_at)
                        VALUES (?, ?, ?, 0.7300, 'HIGH', 'REVIEW', 'rules_v1', 2,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'FRESH', 0,
                                '["REPEATED_PAYMENT_ATTEMPT"]', 'MERCHANT_API', CURRENT_TIMESTAMP)
                        """.formatted(schema), fraudDecisionId, merchantId, paymentAttemptId);
                upgradeJdbcTemplate.update("""
                        INSERT INTO %s.manual_review_cases
                            (id, merchant_id, payment_intent_id, payment_attempt_id, fraud_decision_id,
                             decision_action, status, reason_codes, traffic_origin, cancellation_reason,
                             created_at, updated_at, canceled_at)
                        VALUES (?, ?, ?, ?, ?, 'REVIEW', ?, '["REPEATED_PAYMENT_ATTEMPT"]',
                                'MERCHANT_API', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                                CASE WHEN ? = 'CANCELED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                        """.formatted(schema), reviewCaseId, merchantId, paymentIntentId, paymentAttemptId,
                        fraudDecisionId, status,
                        "CANCELED".equals(status) ? "MERCHANT_CANCELED" : null, status);
            }

            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoints
                        (id, merchant_id, url, signing_secret_prefix, signing_secret_hash,
                         operational_status, signing_secret_generation, created_at)
                    VALUES (?, ?, 'https://review-resolution.example.test/hooks', 'whsec_review_resolution', ?,
                            'DISABLED', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), webhookEndpointId, merchantId,
                    "a".repeat(64), UUID.randomUUID());
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.webhook_endpoint_event_subscriptions
                        (merchant_id, webhook_endpoint_id, event_type, created_at)
                    VALUES (?, ?, 'payment_intent.canceled', CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, webhookEndpointId);

            List<Map<String, Object>> historicalRows = upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema));

            migrateSchema(upgradeDataSource, schema, "28");

            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.manual_review_cases
                    WHERE review_stage = 'PRE_AUTHORIZATION' AND assignment_version = 0
                      AND assigned_operator_id IS NULL AND resolved_at IS NULL
                    """.formatted(schema), Integer.class)).isEqualTo(2);
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT event_type FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ? ORDER BY event_type
                    """.formatted(schema), webhookEndpointId))
                    .extracting(row -> row.get("event_type"))
                    .containsExactly("payment_intent.canceled");
            assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                    UPDATE %s.manual_review_cases
                    SET reason_codes = '["RULES_NOT_ENABLED"]'
                    WHERE merchant_id = ?
                    """.formatted(schema), merchantId))
                    .hasMessageContaining("manual review history is immutable");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(3);

            migrateSchema(upgradeDataSource, schema, "29");

            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(4);
            String approvalSemantics = upgradeJdbcTemplate.queryForObject("""
                    SELECT pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE connamespace = ?::regnamespace
                      AND conname = 'ck_review_resolutions_semantics'
                    """, String.class, schema);
            assertThat(approvalSemantics)
                    .contains("APPROVE", "CONFIRMED_LEGITIMATE", "MANUAL_REVIEW_APPROVED");

            migrateSchema(upgradeDataSource, schema, "30");

            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT event_type FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ? ORDER BY event_type
                    """.formatted(schema), webhookEndpointId))
                    .extracting(row -> row.get("event_type"))
                    .containsExactly("payment_intent.canceled");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29', '30') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(5);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'authentication_challenges'
                    """, Integer.class, schema)).isOne();

            migrateSchema(upgradeDataSource, schema, "31");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29', '30', '31') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(6);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = ?::regclass AND NOT tgisinternal
                      AND tgname IN ('trg_authentication_challenge_history',
                                     'trg_authentication_challenge_insert')
                    """, Integer.class, schema + ".authentication_challenges")).isEqualTo(2);

            migrateSchema(upgradeDataSource, schema, "32");
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".risk_contexts", Integer.class)).isZero();

            migrateSchema(upgradeDataSource, schema, "33");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".feature_definitions", Integer.class)).isEqualTo(17);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = ?::regclass AND NOT tgisinternal
                      AND tgname = 'trg_feature_definitions_immutable'
                    """, Integer.class, schema + ".feature_definitions")).isOne();
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);

            migrateSchema(upgradeDataSource, schema, "34");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29', '30', '31', '32', '33', '34')
                      AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(9);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = ?::regclass AND NOT tgisinternal
                      AND tgname IN ('trg_risk_context_insert', 'trg_risk_context_history',
                                     'trg_risk_context_complete_lineage')
                    """, Integer.class, schema + ".risk_contexts")).isEqualTo(3);
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);

            migrateSchema(upgradeDataSource, schema, "35");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".velocity_observations", Integer.class)).isZero();

            migrateSchema(upgradeDataSource, schema, "36");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = ?::regclass AND NOT tgisinternal
                      AND tgname = 'trg_velocity_observations_immutable'
                    """, Integer.class, schema + ".velocity_observations")).isOne();

            migrateSchema(upgradeDataSource, schema, "37");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".feature_definitions", Integer.class)).isEqualTo(21);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29', '30', '31', '32', '33', '34', '35', '36', '37')
                      AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(12);
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);

            migrateSchema(upgradeDataSource, schema, "38");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'customers'
                      AND column_name = 'email_domain_hash'
                    """, Integer.class, schema)).isOne();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".fraud_lists", Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".fraud_list_evaluations", Integer.class)).isZero();

            migrateSchema(upgradeDataSource, schema, "39");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid IN (?::regclass, ?::regclass, ?::regclass, ?::regclass, ?::regclass)
                      AND NOT tgisinternal AND tgname LIKE 'trg_fraud_list%'
                    """, Integer.class, schema + ".fraud_lists", schema + ".fraud_list_versions",
                    schema + ".fraud_list_entries", schema + ".fraud_list_lifecycle_events",
                    schema + ".fraud_list_evaluations")).isEqualTo(6);
            migrateSchema(upgradeDataSource, schema, "40");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29', '30', '31', '32', '33', '34',
                                      '35', '36', '37', '38', '39', '40') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(15);
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);

            migrateSchema(upgradeDataSource, schema, "41");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".merchant_risk_configuration_guards",
                    Integer.class)).isOne();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".merchant_risk_profile_versions",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".merchant_risk_policy_versions",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".risk_control_evaluations",
                    Integer.class)).isZero();

            migrateSchema(upgradeDataSource, schema, "42");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".feature_definitions", Integer.class)).isEqualTo(22);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('26', '27', '28', '29', '30', '31', '32', '33', '34',
                                      '35', '36', '37', '38', '39', '40', '41', '42')
                      AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(17);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid IN (?::regclass, ?::regclass, ?::regclass, ?::regclass,
                                      ?::regclass, ?::regclass, ?::regclass)
                      AND NOT tgisinternal
                    """, Integer.class,
                    schema + ".merchant_risk_configuration_guards",
                    schema + ".merchant_risk_profile_versions",
                    schema + ".merchant_risk_profile_events",
                    schema + ".merchant_risk_policy_versions",
                    schema + ".merchant_risk_policy_thresholds",
                    schema + ".merchant_risk_policy_events",
                    schema + ".risk_control_evaluations")).isEqualTo(10);
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);

            migrateSchema(upgradeDataSource, schema, "43");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".chargebacks", Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".dispute_offset_allocations", Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".dispute_evidence_packets", Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT event_type FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ? ORDER BY event_type
                    """.formatted(schema), webhookEndpointId))
                    .extracting(row -> row.get("event_type"))
                    .containsExactly("payment_intent.canceled");
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);

            migrateSchema(upgradeDataSource, schema, "44");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid IN (?::regclass, ?::regclass, ?::regclass)
                      AND NOT tgisinternal
                      AND tgname LIKE 'trg_%'
                    """, Integer.class, schema + ".chargebacks",
                    schema + ".dispute_offset_allocations",
                    schema + ".dispute_evidence_packets")).isEqualTo(6);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('43', '44') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(2);

            migrateSchema(upgradeDataSource, schema, "46");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".post_authorization_signals",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".review_refund_actions",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT event_type FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ? ORDER BY event_type
                    """.formatted(schema), webhookEndpointId))
                    .extracting(row -> row.get("event_type"))
                    .containsExactly("payment_intent.canceled");
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('45', '46') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(2);

            migrateSchema(upgradeDataSource, schema, "48");
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".card_testing_incidents",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".incident_evidence_links",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".card_testing_incident_resolutions",
                    Integer.class)).isZero();
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT event_type FROM %s.webhook_endpoint_event_subscriptions
                    WHERE webhook_endpoint_id = ? ORDER BY event_type
                    """.formatted(schema), webhookEndpointId))
                    .extracting(row -> row.get("event_type"))
                    .containsExactly("payment_intent.canceled");
            assertThat(upgradeJdbcTemplate.queryForList("""
                    SELECT review.id, review.payment_intent_id, review.payment_attempt_id,
                           review.fraud_decision_id, review.decision_action, review.status,
                           review.reason_codes, review.traffic_origin, review.cancellation_reason,
                           review.created_at, review.updated_at, review.canceled_at,
                           decision.action, decision.risk_score, decision.ruleset_version
                    FROM %s.manual_review_cases review
                    JOIN %s.fraud_decisions decision ON decision.id = review.fraud_decision_id
                    ORDER BY review.status
                    """.formatted(schema, schema))).isEqualTo(historicalRows);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('47', '48') AND success = TRUE
                    """.formatted(schema), Integer.class)).isEqualTo(2);

            UUID legacyIdempotencyId = UUID.randomUUID();
            upgradeJdbcTemplate.update("""
                    INSERT INTO %s.idempotency_records
                        (id, merchant_id, idempotency_key, operation, request_hash,
                         request_hash_key_version, status, http_status, response_body,
                         created_at, updated_at, expires_at)
                    VALUES (?, ?, 'retained-principal-key', 'customers.create', ?, 'v1',
                            'COMPLETED', 201, '{"id":"retained"}',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP + INTERVAL '24 hours')
                    """.formatted(schema), legacyIdempotencyId, merchantId, "d".repeat(64));

            migrateSchema(upgradeDataSource, schema, "49");
            Map<String, Object> principal = upgradeJdbcTemplate.queryForMap("""
                    SELECT principal_type, principal_id, principal_credential_prefix
                    FROM %s.idempotency_records WHERE id = ?
                    """.formatted(schema), legacyIdempotencyId);
            assertThat(principal.get("principal_type")).isEqualTo("MERCHANT_API");
            assertThat(principal.get("principal_id")).isEqualTo(merchantId);
            assertThat(principal.get("principal_credential_prefix"))
                    .isEqualTo("thaap_");
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'risk_contexts'
                      AND column_name IN ('ip_identity_hmac_aliases',
                                          'device_identity_hmac_aliases',
                                          'session_identity_hmac_aliases')
                    """, Integer.class, schema)).isEqualTo(3);
            assertThat(upgradeJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version = '49' AND success = TRUE
                    """.formatted(schema), Integer.class)).isOne();
        } finally {
            upgradeJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void migrateSchema(DriverManagerDataSource source, String schema, String target) {
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void insertMerchantCustomerAndPaymentIntent(JdbcTemplate source, String schema, UUID merchantId,
            UUID customerId, UUID paymentIntentId, String status) {
        source.update("""
                INSERT INTO %s.merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'refund-ledger-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """.formatted(schema), merchantId, "refund-ledger-" + paymentIntentId);
        source.update("""
                INSERT INTO %s.customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, 'refund-ledger-email-hash', CURRENT_TIMESTAMP)
                """.formatted(schema), customerId, merchantId);
        source.update("""
                INSERT INTO %s.payment_intents (
                    id, merchant_id, customer_id, amount, currency, capture_method, status,
                    metadata, created_at, updated_at
                )
                VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', ?, '{}',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), paymentIntentId, merchantId, customerId, status);
    }

    private void insertLegacyCaptureLedgerEntry(JdbcTemplate source, String schema, UUID merchantId,
            UUID paymentIntentId, String account, String direction) {
        source.update("""
                INSERT INTO %s.ledger_entries (
                    id, merchant_id, transaction_group_id, source_type, source_id,
                    account, direction, amount, currency, created_at
                )
                VALUES (?, ?, ?, 'PAYMENT_INTENT_CAPTURE', ?, ?, ?, 1200, 'USD', CURRENT_TIMESTAMP)
                """.formatted(schema), UUID.randomUUID(), merchantId, paymentIntentId, paymentIntentId,
                account, direction);
    }

    private String captureMethod(JdbcTemplate source, String qualifiedPaymentIntents, UUID paymentIntentId) {
        return source.queryForObject("""
                SELECT capture_method
                FROM %s
                WHERE id = ?
                """.formatted(qualifiedPaymentIntents), String.class, paymentIntentId);
    }

    private boolean columnExists(String tableName, String columnName) {
        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count == 1;
    }

    private boolean indexExists(String tableName, String indexName) {
        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE tablename = ?
                  AND indexname = ?
                """, Integer.class, tableName, indexName);
        return count != null && count == 1;
    }
}
