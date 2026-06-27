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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
        assertThat(indexExists("refunds", "uk_refunds_payment_intent")).isTrue();
        assertThat(columnExists("idempotency_records", "operation")).isTrue();
        assertThat(columnExists("idempotency_records", "expires_at")).isTrue();
        assertThat(columnExists("webhook_deliveries", "attempt_count")).isTrue();
    }

    @Test
    void chapter17UpgradePreservesLegacyOpenAutomaticPaymentSemanticsOnPostgreSql() {
        String schema = "chapter17_upgrade_" + UUID.randomUUID().toString().replace("-", "");
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
    void chapter18UpgradeBackfillsCaptureLedgerPaymentLineageOnPostgreSql() {
        String schema = "chapter18_upgrade_" + UUID.randomUUID().toString().replace("-", "");
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
    void chapter18UpgradeRejectsLegacyRefundedPaymentIntentsOnPostgreSql() {
        String schema = "chapter18_guard_" + UUID.randomUUID().toString().replace("-", "");
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
                VALUES (?, 'chapter18-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """.formatted(schema), merchantId, "chapter18-" + paymentIntentId);
        source.update("""
                INSERT INTO %s.customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, 'chapter18-email-hash', CURRENT_TIMESTAMP)
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
