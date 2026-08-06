package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V87__protect_and_backfill_payment_investigation_relation_anchors
        extends BaseJavaMigration {

    private static final String INSERT = """
            INSERT INTO payment_investigation_relation_anchors
                (id, merchant_id, payment_intent_id, payment_attempt_id,
                 relation_type, relation_identity_hmac, identity_key_version,
                 observed_at, traffic_origin, source_type, source_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        backfill(connection);
        if (connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            protectHistory(connection);
        }
    }

    private void backfill(Connection connection) throws Exception {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT attempt.merchant_id, attempt.payment_intent_id, attempt.id,
                       attempt.traffic_origin, attempt.created_at,
                       payment_method.id, payment_method.card_fingerprint,
                       customer.id, customer.email_hash, customer.phone_hash,
                       risk_context.id, risk_context.observed_at,
                       risk_context.identity_key_version,
                       risk_context.ip_identity_hmac, risk_context.ip_identity_hmac_aliases,
                       risk_context.device_identity_hmac, risk_context.device_identity_hmac_aliases,
                       risk_context.session_identity_hmac, risk_context.session_identity_hmac_aliases
                FROM payment_attempts attempt
                JOIN payment_methods payment_method
                  ON payment_method.merchant_id = attempt.merchant_id
                 AND payment_method.id = attempt.payment_method_id
                JOIN customers customer
                  ON customer.merchant_id = payment_method.merchant_id
                 AND customer.id = payment_method.customer_id
                LEFT JOIN risk_contexts risk_context
                  ON risk_context.merchant_id = attempt.merchant_id
                 AND risk_context.payment_attempt_id = attempt.id
                ORDER BY attempt.merchant_id, attempt.id
                """)) {
            try (ResultSet rows = select.executeQuery();
                    PreparedStatement insert = connection.prepareStatement(INSERT)) {
                while (rows.next()) {
                    UUID merchantId = rows.getObject(1, UUID.class);
                    UUID paymentIntentId = rows.getObject(2, UUID.class);
                    UUID paymentAttemptId = rows.getObject(3, UUID.class);
                    String trafficOrigin = rows.getString(4);
                    Instant attemptObservedAt = rows.getTimestamp(5).toInstant();
                    UUID paymentMethodId = rows.getObject(6, UUID.class);
                    UUID customerId = rows.getObject(8, UUID.class);
                    add(insert, merchantId, paymentIntentId, paymentAttemptId,
                            "SAME_CARD_FINGERPRINT", rows.getString(7), "correlation-v1",
                            attemptObservedAt, trafficOrigin, "PAYMENT_METHOD", paymentMethodId);
                    add(insert, merchantId, paymentIntentId, paymentAttemptId,
                            "SAME_EMAIL", rows.getString(9), "correlation-v1",
                            attemptObservedAt, trafficOrigin, "CUSTOMER", customerId);
                    add(insert, merchantId, paymentIntentId, paymentAttemptId,
                            "SAME_PHONE", rows.getString(10), "correlation-v1",
                            attemptObservedAt, trafficOrigin, "CUSTOMER", customerId);

                    UUID riskContextId = rows.getObject(11, UUID.class);
                    if (riskContextId != null) {
                        Instant riskObservedAt = rows.getTimestamp(12).toInstant();
                        String currentKeyVersion = rows.getString(13);
                        addVersioned(insert, merchantId, paymentIntentId, paymentAttemptId,
                                "SAME_IP", rows.getString(14), rows.getString(15), currentKeyVersion,
                                riskObservedAt, trafficOrigin, riskContextId);
                        addVersioned(insert, merchantId, paymentIntentId, paymentAttemptId,
                                "SAME_DEVICE", rows.getString(16), rows.getString(17), currentKeyVersion,
                                riskObservedAt, trafficOrigin, riskContextId);
                        addVersioned(insert, merchantId, paymentIntentId, paymentAttemptId,
                                "SAME_SESSION", rows.getString(18), rows.getString(19), currentKeyVersion,
                                riskObservedAt, trafficOrigin, riskContextId);
                    }
                }
                insert.executeBatch();
            }
        }
    }

    private void addVersioned(PreparedStatement insert, UUID merchantId, UUID paymentIntentId,
            UUID paymentAttemptId, String relationType, String currentDigest, String aliases,
            String currentKeyVersion, Instant observedAt, String trafficOrigin,
            UUID riskContextId) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        if (currentDigest != null && currentKeyVersion != null) {
            values.put(currentKeyVersion, currentDigest);
        }
        if (aliases != null) {
            for (String alias : aliases.split(",")) {
                int separator = alias.indexOf('=');
                if (separator > 0 && separator < alias.length() - 1) {
                    values.put(alias.substring(0, separator), alias.substring(separator + 1));
                }
            }
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            add(insert, merchantId, paymentIntentId, paymentAttemptId, relationType,
                    value.getValue(), value.getKey(), observedAt, trafficOrigin,
                    "RISK_CONTEXT", riskContextId);
        }
    }

    private void add(PreparedStatement insert, UUID merchantId, UUID paymentIntentId,
            UUID paymentAttemptId, String relationType, String digest, String keyVersion,
            Instant observedAt, String trafficOrigin, String sourceType, UUID sourceId)
            throws Exception {
        if (digest == null || digest.isBlank()) {
            return;
        }
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, merchantId);
        insert.setObject(3, paymentIntentId);
        insert.setObject(4, paymentAttemptId);
        insert.setString(5, relationType);
        insert.setString(6, digest);
        insert.setString(7, keyVersion);
        insert.setTimestamp(8, Timestamp.from(observedAt));
        insert.setString(9, trafficOrigin);
        insert.setString(10, sourceType);
        insert.setObject(11, sourceId);
        insert.setTimestamp(12, Timestamp.from(Instant.now()));
        insert.addBatch();
    }

    private void protectHistory(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION validate_payment_investigation_relation_anchor()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE valid_source boolean;
                    BEGIN
                        IF NEW.source_type = 'CUSTOMER' THEN
                            SELECT EXISTS (
                                SELECT 1 FROM payment_attempts attempt
                                JOIN payment_methods method
                                  ON method.merchant_id = attempt.merchant_id
                                 AND method.id = attempt.payment_method_id
                                JOIN customers customer
                                  ON customer.merchant_id = method.merchant_id
                                 AND customer.id = method.customer_id
                                WHERE attempt.merchant_id = NEW.merchant_id
                                  AND attempt.id = NEW.payment_attempt_id
                                  AND attempt.payment_intent_id = NEW.payment_intent_id
                                  AND customer.id = NEW.source_id
                                  AND ((NEW.relation_type = 'SAME_EMAIL'
                                        AND customer.email_hash = NEW.relation_identity_hmac)
                                    OR (NEW.relation_type = 'SAME_PHONE'
                                        AND customer.phone_hash = NEW.relation_identity_hmac)))
                              INTO valid_source;
                        ELSIF NEW.source_type = 'PAYMENT_METHOD' THEN
                            SELECT EXISTS (
                                SELECT 1 FROM payment_attempts attempt
                                JOIN payment_methods method
                                  ON method.merchant_id = attempt.merchant_id
                                 AND method.id = attempt.payment_method_id
                                WHERE attempt.merchant_id = NEW.merchant_id
                                  AND attempt.id = NEW.payment_attempt_id
                                  AND attempt.payment_intent_id = NEW.payment_intent_id
                                  AND method.id = NEW.source_id
                                  AND method.card_fingerprint = NEW.relation_identity_hmac)
                              INTO valid_source;
                        ELSE
                            SELECT EXISTS (
                                SELECT 1 FROM risk_contexts risk_context
                                WHERE risk_context.merchant_id = NEW.merchant_id
                                  AND risk_context.id = NEW.source_id
                                  AND risk_context.payment_intent_id = NEW.payment_intent_id
                                  AND risk_context.payment_attempt_id = NEW.payment_attempt_id
                                  AND (
                                    (NEW.relation_type = 'SAME_IP' AND (
                                        (risk_context.identity_key_version = NEW.identity_key_version
                                         AND risk_context.ip_identity_hmac = NEW.relation_identity_hmac)
                                        OR POSITION(',' || NEW.identity_key_version || '='
                                            || NEW.relation_identity_hmac || ',' IN ',' ||
                                            COALESCE(risk_context.ip_identity_hmac_aliases, '') || ',') > 0))
                                    OR (NEW.relation_type = 'SAME_DEVICE' AND (
                                        (risk_context.identity_key_version = NEW.identity_key_version
                                         AND risk_context.device_identity_hmac = NEW.relation_identity_hmac)
                                        OR POSITION(',' || NEW.identity_key_version || '='
                                            || NEW.relation_identity_hmac || ',' IN ',' ||
                                            COALESCE(risk_context.device_identity_hmac_aliases, '') || ',') > 0))
                                    OR (NEW.relation_type = 'SAME_SESSION' AND (
                                        (risk_context.identity_key_version = NEW.identity_key_version
                                         AND risk_context.session_identity_hmac = NEW.relation_identity_hmac)
                                        OR POSITION(',' || NEW.identity_key_version || '='
                                            || NEW.relation_identity_hmac || ',' IN ',' ||
                                            COALESCE(risk_context.session_identity_hmac_aliases, '') || ',') > 0))))
                              INTO valid_source;
                        END IF;
                        IF NOT COALESCE(valid_source, false) THEN
                            RAISE EXCEPTION 'Investigation relation anchor does not match source lineage';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE CONSTRAINT TRIGGER trg_payment_investigation_relation_source
                    AFTER INSERT ON payment_investigation_relation_anchors
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION validate_payment_investigation_relation_anchor()
                    """);
            statement.execute("""
                    CREATE FUNCTION reject_payment_investigation_relation_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Investigation relation anchors are append-only';
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_payment_investigation_relation_append_only
                    BEFORE UPDATE OR DELETE ON payment_investigation_relation_anchors
                    FOR EACH ROW EXECUTE FUNCTION reject_payment_investigation_relation_mutation()
                    """);
        }
    }
}

