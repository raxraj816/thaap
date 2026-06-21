package com.thaap.payments.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
        assertThat(columnExists("idempotency_records", "operation")).isTrue();
        assertThat(columnExists("idempotency_records", "expires_at")).isTrue();
        assertThat(columnExists("webhook_deliveries", "attempt_count")).isTrue();
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
