package com.thaap.payments.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionIntelligencePostgreSqlMigrationTests extends MigrationPostgreSqlTestBase {

    @Test
    void freshV1ToV91CreatesTheCompleteDecisionIntelligenceSchemaAndProtection() {
        Database database = database("decision_intelligence_fresh");
        try {
            migrate(database, null);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM %s.flyway_schema_history
                    WHERE version IN ('86','87','88','89','90','91') AND success = TRUE
                    """.formatted(database.schema()), Integer.class)).isEqualTo(6);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name IN (
                      'payment_investigation_relation_anchors',
                      'payment_investigation_saved_views',
                      'review_bulk_action_requests', 'review_bulk_action_items')
                    """, Integer.class, database.schema())).isEqualTo(4);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(DISTINCT trigger_name) FROM information_schema.triggers
                    WHERE trigger_schema = ? AND trigger_name IN (
                      'trg_payment_investigation_relation_append_only',
                      'trg_review_bulk_action_requests_protected',
                      'trg_review_bulk_action_items_protected')
                    """, Integer.class, database.schema())).isEqualTo(3);
        } finally {
            database.jdbc().execute("DROP SCHEMA IF EXISTS " + database.schema() + " CASCADE");
        }
    }

    @Test
    void retainedV85RowsBackfillAliasesThenRepeatedStartupKeepsExactHistory() {
        Database database = database("decision_intelligence_retained");
        try {
            migrate(database, "85");
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID paymentMethodId = UUID.randomUUID();
            UUID paymentIntentId = UUID.randomUUID();
            UUID paymentAttemptId = UUID.randomUUID();
            database.jdbc().update("""
                    INSERT INTO %s.merchants
                      (id, name, status, api_key_prefix, api_key_hash, created_at)
                    VALUES (?, 'decision-intelligence-retained', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), merchantId, "retained-" + merchantId);
            database.jdbc().update("""
                    INSERT INTO %s.customers (id, merchant_id, email_hash, created_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), customerId, merchantId,
                    "email-hash-" + UUID.randomUUID());
            database.jdbc().update("""
                    INSERT INTO %s.payment_methods
                      (id, merchant_id, customer_id, type, token, card_fingerprint,
                       card_bin, card_last4, card_funding, card_country, created_at)
                    VALUES (?, ?, ?, 'CARD', ?, ?, '424242', '4242', 'CREDIT', 'US',
                            CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), paymentMethodId, merchantId, customerId,
                    "token-" + paymentMethodId, "fingerprint-" + paymentMethodId);
            database.jdbc().update("""
                    INSERT INTO %s.payment_intents
                      (id, merchant_id, customer_id, amount, currency, capture_method, status,
                       metadata, traffic_origin, created_at, updated_at)
                    VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', 'REQUIRES_REVIEW', '{}',
                            'MERCHANT_API', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), paymentIntentId, merchantId, customerId);
            database.jdbc().update("""
                    INSERT INTO %s.payment_attempts
                      (id, merchant_id, payment_intent_id, payment_method_id, idempotency_key,
                       status, attempt_number, traffic_origin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'IN_REVIEW', 1, 'MERCHANT_API',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), paymentAttemptId, merchantId,
                    paymentIntentId, paymentMethodId, "retained-attempt-" + paymentAttemptId);

            migrate(database, null);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM %s.payment_investigation_relation_anchors
                    WHERE merchant_id = ? AND payment_intent_id = ?
                    """.formatted(database.schema()), Integer.class,
                    merchantId, paymentIntentId)).isEqualTo(2);
            assertThat(database.jdbc().queryForList("""
                    SELECT relation_type FROM %s.payment_investigation_relation_anchors
                    WHERE merchant_id = ? ORDER BY relation_type
                    """.formatted(database.schema()), String.class, merchantId))
                    .containsExactly("SAME_CARD_FINGERPRINT", "SAME_EMAIL");
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.payment_investigation_relation_anchors
                    SET identity_key_version = 'altered'
                    WHERE merchant_id = ?
                    """.formatted(database.schema()), merchantId))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("append-only");

            migrate(database, null);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM %s.payment_investigation_relation_anchors
                    WHERE merchant_id = ?
                    """.formatted(database.schema()), Integer.class, merchantId)).isEqualTo(2);
            assertThat(database.jdbc().queryForObject("""
                    SELECT MAX(version) FROM %s.flyway_schema_history WHERE success = TRUE
                    """.formatted(database.schema()), String.class)).isEqualTo("91");
        } finally {
            database.jdbc().execute("DROP SCHEMA IF EXISTS " + database.schema() + " CASCADE");
        }
    }

}
