package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V34__protect_risk_context_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION validate_risk_context_insert()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE attempt_intent UUID;
                        DECLARE attempt_origin VARCHAR(32);
                        BEGIN
                            SELECT payment_intent_id, traffic_origin
                              INTO attempt_intent, attempt_origin
                              FROM payment_attempts
                             WHERE merchant_id = NEW.merchant_id
                               AND id = NEW.payment_attempt_id;
                            IF NOT FOUND
                               OR attempt_intent IS DISTINCT FROM NEW.payment_intent_id
                               OR attempt_origin IS DISTINCT FROM NEW.traffic_origin THEN
                                RAISE EXCEPTION 'risk context payment-attempt lineage mismatch';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_risk_context_insert
                        BEFORE INSERT ON risk_contexts
                        FOR EACH ROW EXECUTE FUNCTION validate_risk_context_insert()
                        """);
                statement.execute("""
                        CREATE FUNCTION enforce_risk_context_history()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF TG_OP = 'DELETE'
                               OR OLD.id IS DISTINCT FROM NEW.id
                               OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                               OR OLD.payment_intent_id IS DISTINCT FROM NEW.payment_intent_id
                               OR OLD.payment_attempt_id IS DISTINCT FROM NEW.payment_attempt_id
                               OR OLD.ip_identity_hmac IS DISTINCT FROM NEW.ip_identity_hmac
                               OR OLD.user_agent_identity_hmac IS DISTINCT FROM NEW.user_agent_identity_hmac
                               OR OLD.device_identity_hmac IS DISTINCT FROM NEW.device_identity_hmac
                               OR OLD.session_identity_hmac IS DISTINCT FROM NEW.session_identity_hmac
                               OR OLD.identity_key_version IS DISTINCT FROM NEW.identity_key_version
                               OR OLD.ip_country IS DISTINCT FROM NEW.ip_country
                               OR OLD.shipping_country IS DISTINCT FROM NEW.shipping_country
                               OR OLD.checkout_channel IS DISTINCT FROM NEW.checkout_channel
                               OR OLD.customer_account_age_days IS DISTINCT FROM NEW.customer_account_age_days
                               OR OLD.login_age_minutes IS DISTINCT FROM NEW.login_age_minutes
                               OR OLD.bot_score_bps IS DISTINCT FROM NEW.bot_score_bps
                               OR OLD.captcha_outcome IS DISTINCT FROM NEW.captcha_outcome
                               OR OLD.observed_at IS DISTINCT FROM NEW.observed_at
                               OR OLD.traffic_origin IS DISTINCT FROM NEW.traffic_origin
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR OLD.fraud_decision_id IS NOT NULL
                               OR NEW.fraud_decision_id IS NULL THEN
                                RAISE EXCEPTION 'risk context history is immutable';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_risk_context_history
                        BEFORE UPDATE OR DELETE ON risk_contexts
                        FOR EACH ROW EXECUTE FUNCTION enforce_risk_context_history()
                        """);
                statement.execute("""
                        CREATE FUNCTION require_complete_risk_context_lineage()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE current_context risk_contexts%ROWTYPE;
                        BEGIN
                            SELECT * INTO current_context FROM risk_contexts WHERE id = NEW.id;
                            IF NOT FOUND OR current_context.fraud_decision_id IS NULL OR NOT EXISTS (
                                SELECT 1 FROM fraud_decisions
                                 WHERE merchant_id = current_context.merchant_id
                                   AND id = current_context.fraud_decision_id
                                   AND payment_attempt_id = current_context.payment_attempt_id
                                   AND traffic_origin = current_context.traffic_origin
                            ) THEN
                                RAISE EXCEPTION 'risk context requires complete fraud-decision lineage';
                            END IF;
                            RETURN NULL;
                        END; $$
                        """);
                statement.execute("""
                        CREATE CONSTRAINT TRIGGER trg_risk_context_complete_lineage
                        AFTER INSERT OR UPDATE ON risk_contexts
                        DEFERRABLE INITIALLY DEFERRED
                        FOR EACH ROW EXECUTE FUNCTION require_complete_risk_context_lineage()
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("RiskContext history protection is unsupported for " + product);
            }
        }
    }
}
