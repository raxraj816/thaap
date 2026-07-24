package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V42__protect_merchant_risk_policy_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        dropFeatureTrigger(connection, product);
        try {
            insertRiskTierDefinition(connection);
        } finally {
            recreateFeatureTrigger(connection, product);
        }
        if ("PostgreSQL".equals(product)) {
            createPostgreSqlGuards(connection);
        } else if (!"H2".equals(product)) {
            throw new IllegalStateException("Merchant risk policy migration is unsupported for " + product);
        }
    }

    private void insertRiskTierDefinition(Connection connection) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO feature_definitions
                    (id, feature_code, entity_type, value_type, owner, feature_version,
                     description, max_age_ms, created_at)
                VALUES (?, 'merchant.risk_tier', 'PAYMENT_ATTEMPT', 'STRING', 'FRAUD_RISK', 'v1',
                        'Governed merchant risk profile tier active at decision time', NULL, ?)
                """)) {
            insert.setObject(1, UUID.fromString("40000000-0000-0000-0000-000000000001"));
            insert.setTimestamp(2, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    private void dropFeatureTrigger(Connection connection, String product) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PostgreSQL".equals(product)
                    ? "DROP TRIGGER trg_feature_definitions_immutable ON feature_definitions"
                    : "DROP TRIGGER trg_feature_definitions_immutable");
        }
    }

    private void recreateFeatureTrigger(Connection connection, String product) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE TRIGGER trg_feature_definitions_immutable
                        BEFORE INSERT OR UPDATE OR DELETE ON feature_definitions
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_feature_mutation()
                        """);
            } else if ("H2".equals(product)) {
                statement.execute("""
                        CREATE TRIGGER trg_feature_definitions_immutable
                        BEFORE INSERT, UPDATE, DELETE ON feature_definitions
                        AS 'org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                        throws java.sql.SQLException {
                                    throw new java.sql.SQLException("feature lineage is immutable");
                                }
                            };
                        }'
                        """);
            }
        }
    }

    private void createPostgreSqlGuards(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION create_merchant_risk_configuration_guard()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        INSERT INTO merchant_risk_configuration_guards (merchant_id, created_at)
                        VALUES (NEW.id, NEW.created_at);
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_merchants_create_risk_configuration_guard
                    AFTER INSERT ON merchants FOR EACH ROW
                    EXECUTE FUNCTION create_merchant_risk_configuration_guard()
                    """);
            statement.execute("""
                    CREATE FUNCTION reject_merchant_risk_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'merchant risk configuration history is immutable';
                    END; $$
                    """);
            for (String table : new String[] {"merchant_risk_configuration_guards",
                    "merchant_risk_profile_versions", "merchant_risk_profile_events",
                    "merchant_risk_policy_versions", "merchant_risk_policy_thresholds",
                    "merchant_risk_policy_events", "risk_control_evaluations"}) {
                statement.execute("CREATE TRIGGER trg_" + table + "_immutable BEFORE UPDATE OR DELETE ON "
                        + table + " FOR EACH ROW EXECUTE FUNCTION reject_merchant_risk_history_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION validate_merchant_risk_actor(
                        target_merchant UUID, target_actor UUID)
                    RETURNS void LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM risk_operators
                             WHERE merchant_id = target_merchant AND id = target_actor
                               AND status = 'ACTIVE' AND role = 'RISK_MANAGER') THEN
                            RAISE EXCEPTION 'active RISK_MANAGER actor is required';
                        END IF;
                    END; $$
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_merchant_risk_profile_event()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE creator UUID;
                    DECLARE version_created TIMESTAMP(6) WITH TIME ZONE;
                    BEGIN
                        PERFORM 1 FROM merchant_risk_configuration_guards
                         WHERE merchant_id = NEW.merchant_id FOR UPDATE;
                        IF NOT FOUND THEN
                            RAISE EXCEPTION 'merchant risk configuration guard is missing';
                        END IF;
                        PERFORM validate_merchant_risk_actor(NEW.merchant_id, NEW.actor_operator_id);
                        SELECT created_by_operator_id, created_at INTO creator, version_created
                          FROM merchant_risk_profile_versions
                         WHERE merchant_id = NEW.merchant_id AND id = NEW.profile_version_id;
                        IF NOT FOUND OR NEW.occurred_at < version_created THEN
                            RAISE EXCEPTION 'profile lifecycle event precedes its version';
                        END IF;
                        IF NEW.event_type = 'SUBMITTED' AND EXISTS (
                            SELECT 1 FROM merchant_risk_profile_events
                             WHERE merchant_id = NEW.merchant_id
                               AND profile_version_id = NEW.profile_version_id) THEN
                            RAISE EXCEPTION 'only a draft profile can be submitted';
                        ELSIF NEW.event_type = 'APPROVED' THEN
                            IF NEW.actor_operator_id = creator THEN
                                RAISE EXCEPTION 'profile creator cannot approve the same version';
                            END IF;
                            IF NOT EXISTS (SELECT 1 FROM merchant_risk_profile_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND profile_version_id = NEW.profile_version_id
                                  AND event_type = 'SUBMITTED')
                               OR EXISTS (SELECT 1 FROM merchant_risk_profile_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND profile_version_id = NEW.profile_version_id
                                  AND event_type IN ('APPROVED', 'ACTIVATED', 'RETIRED')) THEN
                                RAISE EXCEPTION 'profile approval requires pending approval';
                            END IF;
                        ELSIF NEW.event_type = 'ACTIVATED' THEN
                            IF NOT EXISTS (SELECT 1 FROM merchant_risk_profile_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND profile_version_id = NEW.profile_version_id
                                  AND event_type = 'APPROVED')
                               OR EXISTS (SELECT 1 FROM merchant_risk_profile_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND profile_version_id = NEW.profile_version_id
                                  AND event_type IN ('ACTIVATED', 'RETIRED')) THEN
                                RAISE EXCEPTION 'profile activation requires approved state';
                            END IF;
                            IF EXISTS (
                                SELECT 1 FROM merchant_risk_profile_versions other
                                 WHERE other.merchant_id = NEW.merchant_id
                                   AND other.id <> NEW.profile_version_id
                                   AND EXISTS (SELECT 1 FROM merchant_risk_profile_events active_event
                                        WHERE active_event.merchant_id = other.merchant_id
                                          AND active_event.profile_version_id = other.id
                                          AND active_event.event_type = 'ACTIVATED')
                                   AND NOT EXISTS (SELECT 1 FROM merchant_risk_profile_events retired_event
                                        WHERE retired_event.merchant_id = other.merchant_id
                                          AND retired_event.profile_version_id = other.id
                                          AND retired_event.event_type = 'RETIRED')) THEN
                                RAISE EXCEPTION 'merchant already has an active risk profile';
                            END IF;
                        ELSIF NEW.event_type = 'RETIRED' THEN
                            IF NOT EXISTS (SELECT 1 FROM merchant_risk_profile_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND profile_version_id = NEW.profile_version_id
                                  AND event_type = 'ACTIVATED')
                               OR EXISTS (SELECT 1 FROM merchant_risk_profile_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND profile_version_id = NEW.profile_version_id
                                  AND event_type = 'RETIRED') THEN
                                RAISE EXCEPTION 'only an active profile can be retired';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_merchant_risk_profile_event_semantics
                    BEFORE INSERT ON merchant_risk_profile_events FOR EACH ROW
                    EXECUTE FUNCTION validate_merchant_risk_profile_event()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_merchant_risk_policy_threshold()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM merchant_risk_policy_events
                            WHERE merchant_id = NEW.merchant_id
                              AND policy_version_id = NEW.policy_version_id) THEN
                            RAISE EXCEPTION 'policy thresholds are immutable after submission';
                        END IF;
                        IF NEW.feature_code NOT IN (
                            'merchant.risk_tier', 'payment.amount_minor', 'payment.attempt_number',
                            'payment.capture_method', 'payment.currency', 'traffic.origin',
                            'card.country', 'card.funding', 'risk.context_present',
                            'risk.ip_country', 'risk.shipping_country', 'risk.checkout_channel',
                            'risk.customer_account_age_days', 'risk.login_age_minutes',
                            'risk.bot_score_bps', 'risk.captcha_outcome',
                            'risk.card_ip_country_match', 'risk.card_shipping_country_match',
                            'velocity.card_attempts_10m', 'velocity.ip_attempts_10m',
                            'velocity.customer_attempts_1h', 'velocity.merchant_attempts_1m') THEN
                            RAISE EXCEPTION 'feature is not allowlisted for merchant policy';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_merchant_risk_policy_threshold_semantics
                    BEFORE INSERT ON merchant_risk_policy_thresholds FOR EACH ROW
                    EXECUTE FUNCTION validate_merchant_risk_policy_threshold()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_merchant_risk_policy_event()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE creator UUID;
                    DECLARE version_created TIMESTAMP(6) WITH TIME ZONE;
                    BEGIN
                        PERFORM 1 FROM merchant_risk_configuration_guards
                         WHERE merchant_id = NEW.merchant_id FOR UPDATE;
                        IF NOT FOUND THEN
                            RAISE EXCEPTION 'merchant risk configuration guard is missing';
                        END IF;
                        PERFORM validate_merchant_risk_actor(NEW.merchant_id, NEW.actor_operator_id);
                        SELECT created_by_operator_id, created_at INTO creator, version_created
                          FROM merchant_risk_policy_versions
                         WHERE merchant_id = NEW.merchant_id AND id = NEW.policy_version_id;
                        IF NOT FOUND OR NEW.occurred_at < version_created THEN
                            RAISE EXCEPTION 'policy lifecycle event precedes its version';
                        END IF;
                        IF NEW.event_type = 'SUBMITTED' THEN
                            IF EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id)
                               OR NOT EXISTS (SELECT 1 FROM merchant_risk_policy_thresholds
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id) THEN
                                RAISE EXCEPTION 'only a non-empty draft policy can be submitted';
                            END IF;
                        ELSIF NEW.event_type = 'APPROVED' THEN
                            IF NEW.actor_operator_id = creator THEN
                                RAISE EXCEPTION 'policy creator cannot approve the same version';
                            END IF;
                            IF NOT EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id
                                  AND event_type = 'SUBMITTED')
                               OR EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id
                                  AND event_type IN ('APPROVED', 'ACTIVATED', 'RETIRED')) THEN
                                RAISE EXCEPTION 'policy approval requires pending approval';
                            END IF;
                        ELSIF NEW.event_type = 'ACTIVATED' THEN
                            IF NOT EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id
                                  AND event_type = 'APPROVED')
                               OR EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id
                                  AND event_type IN ('ACTIVATED', 'RETIRED')) THEN
                                RAISE EXCEPTION 'policy activation requires approved state';
                            END IF;
                            IF EXISTS (
                                SELECT 1 FROM merchant_risk_policy_versions other
                                 WHERE other.merchant_id = NEW.merchant_id
                                   AND other.id <> NEW.policy_version_id
                                   AND EXISTS (SELECT 1 FROM merchant_risk_policy_events active_event
                                        WHERE active_event.merchant_id = other.merchant_id
                                          AND active_event.policy_version_id = other.id
                                          AND active_event.event_type = 'ACTIVATED')
                                   AND NOT EXISTS (SELECT 1 FROM merchant_risk_policy_events retired_event
                                        WHERE retired_event.merchant_id = other.merchant_id
                                          AND retired_event.policy_version_id = other.id
                                          AND retired_event.event_type = 'RETIRED')) THEN
                                RAISE EXCEPTION 'merchant already has an active risk policy';
                            END IF;
                        ELSIF NEW.event_type = 'RETIRED' THEN
                            IF NOT EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id
                                  AND event_type = 'ACTIVATED')
                               OR EXISTS (SELECT 1 FROM merchant_risk_policy_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND policy_version_id = NEW.policy_version_id
                                  AND event_type = 'RETIRED') THEN
                                RAISE EXCEPTION 'only an active policy can be retired';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_merchant_risk_policy_event_semantics
                    BEFORE INSERT ON merchant_risk_policy_events FOR EACH ROW
                    EXECUTE FUNCTION validate_merchant_risk_policy_event()
                    """);
        }
    }
}
