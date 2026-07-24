package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FeatureLineageDatabaseContractTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FeatureValueRepository featureValueRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void pointInTimeJoinExcludesBeforeAtAfterDecisionPartitions() {
        Instant decisionStart = Instant.parse("2026-07-11T12:00:00.123456Z");
        Lineage before = createLineage(decisionStart, decisionStart.minus(1, ChronoUnit.MICROS));
        Lineage at = createLineage(decisionStart, decisionStart);
        Lineage after = createLineage(decisionStart, decisionStart.plus(1, ChronoUnit.MICROS));

        assertThat(featureValueRepository.findPointInTimeForDecision(before.merchantId(), before.decisionId()))
                .extracting(FeatureValue::typedValue).containsExactly(1200L);
        assertThat(featureValueRepository.findPointInTimeForDecision(at.merchantId(), at.decisionId()))
                .extracting(FeatureValue::typedValue).containsExactly(1200L);
        assertThat(featureValueRepository.findPointInTimeForDecision(after.merchantId(), after.decisionId()))
                .isEmpty();
    }

    @Test
    void databaseRejectsEveryTypedColumnAmbiguityAndTimeInversion() {
        Lineage lineage = createBaseLineage(Instant.parse("2026-07-11T12:00:00.123456Z"));
        UUID definitionId = definitionId("payment.amount_minor");
        Instant observedAt = Instant.parse("2026-07-11T11:59:59Z");
        Instant computedAt = Instant.parse("2026-07-11T12:00:01Z");

        assertThatThrownBy(() -> insertFeatureValue(lineage, definitionId, "LONG", null, "1200",
                observedAt, computedAt)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertFeatureValue(lineage, definitionId, "LONG", 1200L, "1200",
                observedAt, computedAt)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertFeatureValue(lineage, definitionId, "LONG", null, null,
                observedAt, computedAt)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertFeatureValue(lineage, definitionId, "LONG", 1200L, null,
                computedAt, observedAt)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseBindsEveryDeclaredValueTypeToTheReferencedDefinitionType() {
        Instant decisionStart = Instant.parse("2026-07-11T12:00:00.123456Z");
        for (String code : FeatureCatalogue.requiredCodes()) {
            FeatureValueType requiredType = FeatureCatalogue.requiredType(code);
            Lineage compatible = createBaseLineage(decisionStart);
            assertThatCode(() -> insertDeclaredFeatureValue(
                    compatible, definitionId(code), code, requiredType, decisionStart))
                    .doesNotThrowAnyException();

            for (FeatureValueType declaredType : FeatureValueType.values()) {
                if (declaredType == requiredType) {
                    continue;
                }
                Lineage incompatible = createBaseLineage(decisionStart);
                assertThatThrownBy(() -> insertDeclaredFeatureValue(
                        incompatible, definitionId(code), code, declaredType, decisionStart))
                        .as("%s must reject declared type %s", code, declaredType)
                        .isInstanceOf(DataAccessException.class);
            }
        }
    }

    @Test
    void databaseRejectsCrossTenantAttemptLineage() {
        Instant decisionStart = Instant.parse("2026-07-11T12:00:00Z");
        Lineage owner = createBaseLineage(decisionStart);
        Lineage attacker = createBaseLineage(decisionStart);
        UUID definitionId = definitionId("payment.amount_minor");

        Lineage substituted = new Lineage(attacker.merchantId(), owner.attemptId(), attacker.decisionId());
        assertThatThrownBy(() -> insertFeatureValue(substituted, definitionId, "LONG", 1200L, null,
                decisionStart, decisionStart)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertFeatureValue(owner, definitionId, "LONG", 1200L, null,
                decisionStart, decisionStart, "SYNTHETIC_SCENARIO")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO feature_values
                    (id, merchant_id, payment_attempt_id, feature_definition_id, feature_code, feature_version,
                     value_type, long_value, observed_at, computed_at, traffic_origin, created_at)
                VALUES (?, ?, ?, ?, 'card.country', 'v1', 'LONG', 1200, ?, ?, 'MERCHANT_API', ?)
                """, UUID.randomUUID(), owner.merchantId(), owner.attemptId(), definitionId,
                sqlTime(decisionStart), sqlTime(decisionStart), sqlTime(decisionStart)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void definitionsAndHistoricalValuesCannotBeMutated() {
        Lineage lineage = createLineage(Instant.parse("2026-07-11T12:00:00Z"),
                Instant.parse("2026-07-11T11:59:59Z"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE feature_definitions SET description = 'rewritten'
                WHERE feature_code = 'payment.amount_minor' AND feature_version = 'v1'
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE feature_values SET long_value = 9999
                WHERE merchant_id = ? AND payment_attempt_id = ?
                """, lineage.merchantId(), lineage.attemptId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fraud_decisions SET max_feature_age_ms = 9999 WHERE id = ?
                """, lineage.decisionId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM feature_values WHERE merchant_id = ? AND payment_attempt_id = ?
                """, lineage.merchantId(), lineage.attemptId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM fraud_decisions WHERE id = ?
                """, lineage.decisionId())).isInstanceOf(DataAccessException.class);
    }

    private Lineage createLineage(Instant decisionStart, Instant observedAt) {
        Lineage lineage = createBaseLineage(decisionStart);
        insertFeatureValue(lineage, definitionId("payment.amount_minor"), "LONG", 1200L, null,
                observedAt, decisionStart.plus(2, ChronoUnit.MICROS));
        return lineage;
    }

    private Lineage createBaseLineage(Instant decisionStart) {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        String unique = merchantId.toString();
        jdbcTemplate.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, ?, 'ACTIVE', 'thaap_test', ?, ?)
                """, merchantId, "merchant-" + unique, "hash-" + unique, sqlTime(decisionStart.minusSeconds(10)));
        jdbcTemplate.update("""
                INSERT INTO customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, ?, ?)
                """, customerId, merchantId, "email-" + unique, sqlTime(decisionStart.minusSeconds(9)));
        jdbcTemplate.update("""
                INSERT INTO payment_methods
                    (id, merchant_id, customer_id, type, token, card_fingerprint, card_bin, card_last4,
                     card_funding, card_country, created_at)
                VALUES (?, ?, ?, 'CARD', ?, ?, '424242', '4242', 'CREDIT', 'US', ?)
                """, paymentMethodId, merchantId, customerId, "token-" + unique, "fingerprint-" + unique,
                sqlTime(decisionStart.minusSeconds(8)));
        jdbcTemplate.update("""
                INSERT INTO payment_intents
                    (id, merchant_id, customer_id, amount, currency, capture_method, status, metadata,
                     traffic_origin, created_at, updated_at)
                VALUES (?, ?, ?, 1200, 'USD', 'MANUAL', 'PROCESSING', '{}', 'MERCHANT_API', ?, ?)
                """, paymentIntentId, merchantId, customerId, sqlTime(decisionStart.minusSeconds(7)),
                sqlTime(decisionStart.minusSeconds(7)));
        jdbcTemplate.update("""
                INSERT INTO payment_attempts
                    (id, merchant_id, payment_intent_id, payment_method_id, idempotency_key, status,
                     attempt_number, traffic_origin, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'READY_FOR_PROCESSOR', 1, 'MERCHANT_API', ?, ?)
                """, attemptId, merchantId, paymentIntentId, paymentMethodId, "idem-" + unique,
                sqlTime(decisionStart.minusSeconds(1)), sqlTime(decisionStart.minusSeconds(1)));
        jdbcTemplate.update("""
                INSERT INTO fraud_decisions
                    (id, merchant_id, payment_attempt_id, risk_score, risk_level, action, model_version,
                     ruleset_version, latency_ms, decision_started_at, decision_completed_at,
                     feature_freshness_status, max_feature_age_ms, reason_codes, traffic_origin, created_at)
                VALUES (?, ?, ?, 0.0000, 'LOW', 'ALLOW', NULL, 'rules_stub_v1', 0, ?, ?,
                        'FRESH', 0, '["RULES_NOT_ENABLED"]', 'MERCHANT_API', ?)
                """, decisionId, merchantId, attemptId, sqlTime(decisionStart), sqlTime(decisionStart),
                sqlTime(decisionStart));
        return new Lineage(merchantId, attemptId, decisionId);
    }

    private UUID definitionId(String code) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM feature_definitions WHERE feature_code = ? AND feature_version = 'v1'
                """, UUID.class, code);
    }

    private void insertFeatureValue(Lineage lineage, UUID definitionId, String valueType, Long longValue,
            String stringValue, Instant observedAt, Instant computedAt) {
        insertFeatureValue(lineage, definitionId, valueType, longValue, stringValue, observedAt, computedAt,
                "MERCHANT_API");
    }

    private void insertFeatureValue(Lineage lineage, UUID definitionId, String valueType, Long longValue,
            String stringValue, Instant observedAt, Instant computedAt, String trafficOrigin) {
        jdbcTemplate.update("""
                INSERT INTO feature_values
                    (id, merchant_id, payment_attempt_id, feature_definition_id, feature_code, feature_version,
                     value_type, long_value, string_value, boolean_value, decimal_value, observed_at, computed_at,
                     traffic_origin, created_at)
                VALUES (?, ?, ?, ?, 'payment.amount_minor', 'v1', ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                """, UUID.randomUUID(), lineage.merchantId(), lineage.attemptId(), definitionId, valueType,
                longValue, stringValue, sqlTime(observedAt), sqlTime(computedAt), trafficOrigin, sqlTime(computedAt));
    }

    private void insertDeclaredFeatureValue(Lineage lineage, UUID definitionId, String code,
            FeatureValueType declaredType, Instant timestamp) {
        Long longValue = declaredType == FeatureValueType.LONG ? 1200L : null;
        String stringValue = declaredType == FeatureValueType.STRING ? "sample" : null;
        Boolean booleanValue = declaredType == FeatureValueType.BOOLEAN ? Boolean.TRUE : null;
        BigDecimal decimalValue = declaredType == FeatureValueType.DECIMAL ? new BigDecimal("1.250000") : null;
        jdbcTemplate.update("""
                INSERT INTO feature_values
                    (id, merchant_id, payment_attempt_id, feature_definition_id, feature_code, feature_version,
                     value_type, long_value, string_value, boolean_value, decimal_value, observed_at, computed_at,
                     traffic_origin, created_at)
                VALUES (?, ?, ?, ?, ?, 'v1', ?, ?, ?, ?, ?, ?, ?, 'MERCHANT_API', ?)
                """, UUID.randomUUID(), lineage.merchantId(), lineage.attemptId(), definitionId, code,
                declaredType.name(), longValue, stringValue, booleanValue, decimalValue,
                sqlTime(timestamp), sqlTime(timestamp), sqlTime(timestamp));
    }

    private java.sql.Timestamp sqlTime(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private record Lineage(UUID merchantId, UUID attemptId, UUID decisionId) {
    }
}
