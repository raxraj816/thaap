package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V31__protect_authentication_challenge_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION enforce_authentication_challenge_history()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE decision_action VARCHAR(32);
                        DECLARE authorization_status VARCHAR(32);
                        BEGIN
                            IF TG_OP = 'DELETE' OR OLD.status <> 'PENDING'
                               OR OLD.id IS DISTINCT FROM NEW.id
                               OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                               OR OLD.payment_intent_id IS DISTINCT FROM NEW.payment_intent_id
                               OR OLD.payment_attempt_id IS DISTINCT FROM NEW.payment_attempt_id
                               OR OLD.fraud_decision_id IS DISTINCT FROM NEW.fraud_decision_id
                               OR OLD.source IS DISTINCT FROM NEW.source
                               OR OLD.client_secret_prefix IS DISTINCT FROM NEW.client_secret_prefix
                               OR OLD.client_secret_sha256 IS DISTINCT FROM NEW.client_secret_sha256
                               OR OLD.client_secret_identity_hmac IS DISTINCT FROM NEW.client_secret_identity_hmac
                               OR OLD.client_secret_key_version IS DISTINCT FROM NEW.client_secret_key_version
                               OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
                               OR OLD.traffic_origin IS DISTINCT FROM NEW.traffic_origin
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR NEW.status = 'PENDING' THEN
                                RAISE EXCEPTION 'authentication challenge history is immutable';
                            END IF;
                            IF OLD.authorization_id IS DISTINCT FROM NEW.authorization_id THEN
                                IF OLD.source <> 'FRAUD_ENGINE' OR OLD.authorization_id IS NOT NULL
                                   OR NEW.status <> 'SUCCEEDED' OR NEW.authorization_id IS NULL THEN
                                    RAISE EXCEPTION 'authentication authorization attachment is invalid';
                                END IF;
                            END IF;
                            IF NEW.authorization_id IS NOT NULL THEN
                                SELECT status INTO authorization_status FROM authorizations
                                WHERE merchant_id = NEW.merchant_id
                                  AND id = NEW.authorization_id
                                  AND payment_attempt_id = NEW.payment_attempt_id;
                                IF NOT FOUND OR (NEW.status = 'SUCCEEDED' AND authorization_status <> 'APPROVED') THEN
                                    RAISE EXCEPTION 'authentication authorization lineage mismatch';
                                END IF;
                            END IF;
                            SELECT action INTO decision_action FROM fraud_decisions
                            WHERE merchant_id = NEW.merchant_id
                              AND id = NEW.fraud_decision_id
                              AND payment_attempt_id = NEW.payment_attempt_id;
                            IF NOT FOUND OR (NEW.source = 'FRAUD_ENGINE' AND decision_action <> 'REQUEST_3DS') THEN
                                RAISE EXCEPTION 'authentication fraud-decision lineage mismatch';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_authentication_challenge_history
                        BEFORE UPDATE OR DELETE ON authentication_challenges
                        FOR EACH ROW EXECUTE FUNCTION enforce_authentication_challenge_history()
                        """);
                statement.execute("""
                        CREATE FUNCTION validate_authentication_challenge_insert()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE attempt_intent UUID;
                        DECLARE decision_action VARCHAR(32);
                        DECLARE authorization_status VARCHAR(32);
                        BEGIN
                            SELECT payment_intent_id
                              INTO attempt_intent
                              FROM payment_attempts
                             WHERE merchant_id = NEW.merchant_id AND id = NEW.payment_attempt_id;
                            SELECT action INTO decision_action FROM fraud_decisions
                             WHERE merchant_id = NEW.merchant_id
                               AND id = NEW.fraud_decision_id
                               AND payment_attempt_id = NEW.payment_attempt_id;
                            IF attempt_intent IS DISTINCT FROM NEW.payment_intent_id
                               OR decision_action IS NULL
                               OR (NEW.source = 'FRAUD_ENGINE' AND decision_action <> 'REQUEST_3DS') THEN
                                RAISE EXCEPTION 'authentication challenge lineage mismatch';
                            END IF;
                            IF NEW.source = 'PROCESSOR' THEN
                                SELECT status INTO authorization_status FROM authorizations
                                 WHERE merchant_id = NEW.merchant_id
                                   AND id = NEW.authorization_id
                                   AND payment_attempt_id = NEW.payment_attempt_id;
                                IF authorization_status IS DISTINCT FROM 'REQUIRES_ACTION' THEN
                                    RAISE EXCEPTION 'processor authentication source mismatch';
                                END IF;
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_authentication_challenge_insert
                        BEFORE INSERT ON authentication_challenges
                        FOR EACH ROW EXECUTE FUNCTION validate_authentication_challenge_insert()
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException(
                        "Authentication challenge history protection is unsupported for " + product);
            }
        }
    }
}
