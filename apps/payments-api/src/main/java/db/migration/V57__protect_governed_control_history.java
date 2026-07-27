package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V57__protect_governed_control_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_governed_control_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Governed control evidence is append-only';
                    END; $$
                    """);
            for (String table : new String[] {"authored_rule_events",
                    "risk_safety_exception_events", "authored_rule_evaluations"}) {
                statement.execute("CREATE TRIGGER trg_" + table + "_append_only "
                        + "BEFORE UPDATE OR DELETE ON " + table + " FOR EACH ROW "
                        + "EXECUTE FUNCTION reject_governed_control_history_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION protect_authored_rule_version()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Authored rule versions cannot be deleted'; END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.rule_code <> OLD.rule_code OR NEW.display_name <> OLD.display_name
                           OR NEW.version_number <> OLD.version_number OR NEW.schema_version <> OLD.schema_version
                           OR NEW.canonical_rule_json <> OLD.canonical_rule_json OR NEW.rule_digest <> OLD.rule_digest
                           OR NEW.candidate_action <> OLD.candidate_action OR NEW.reason_code <> OLD.reason_code
                           OR NEW.feature_catalogue_version <> OLD.feature_catalogue_version
                           OR NEW.created_by_operator_id <> OLD.created_by_operator_id
                           OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Authored rule immutable identity cannot change';
                        END IF;
                        IF NOT ((OLD.lifecycle_status = 'DRAFT' AND NEW.lifecycle_status = 'SUBMITTED')
                            OR (OLD.lifecycle_status = 'SUBMITTED' AND NEW.lifecycle_status = 'APPROVED')
                            OR (OLD.lifecycle_status = 'APPROVED' AND NEW.lifecycle_status IN ('ACTIVE','RETIRED'))
                            OR (OLD.lifecycle_status = 'ACTIVE' AND NEW.lifecycle_status = 'RETIRED')
                            OR (OLD.lifecycle_status = 'RETIRED' AND NEW.lifecycle_status = 'ACTIVE')) THEN
                            RAISE EXCEPTION 'Authored rule lifecycle transition is invalid';
                        END IF;
                        IF NEW.projection_version <> OLD.projection_version + 1 THEN
                            RAISE EXCEPTION 'Authored rule projection version must advance exactly once';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_authored_rule_versions_protected_history
                    BEFORE UPDATE OR DELETE ON authored_rule_versions
                    FOR EACH ROW EXECUTE FUNCTION protect_authored_rule_version()
                    """);
            statement.execute("""
                    CREATE FUNCTION protect_risk_safety_exception_version()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Safety exception versions cannot be deleted'; END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.exception_code <> OLD.exception_code OR NEW.version_number <> OLD.version_number
                           OR NEW.schema_version <> OLD.schema_version OR NEW.source_rule_version_id <> OLD.source_rule_version_id
                           OR NEW.subject_type <> OLD.subject_type OR NEW.subject_identity_hmac <> OLD.subject_identity_hmac
                           OR NEW.subject_hmac_key_version <> OLD.subject_hmac_key_version
                           OR NEW.reason_code <> OLD.reason_code OR NEW.valid_from <> OLD.valid_from
                           OR NEW.expires_at <> OLD.expires_at OR NEW.canonical_exception_json <> OLD.canonical_exception_json
                           OR NEW.exception_digest <> OLD.exception_digest
                           OR NEW.created_by_operator_id <> OLD.created_by_operator_id OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Safety exception immutable identity cannot change';
                        END IF;
                        IF NOT ((OLD.lifecycle_status = 'DRAFT' AND NEW.lifecycle_status = 'SUBMITTED')
                            OR (OLD.lifecycle_status = 'SUBMITTED' AND NEW.lifecycle_status = 'APPROVED')
                            OR (OLD.lifecycle_status = 'APPROVED' AND NEW.lifecycle_status IN ('ACTIVE','RETIRED'))
                            OR (OLD.lifecycle_status = 'ACTIVE' AND NEW.lifecycle_status = 'RETIRED')) THEN
                            RAISE EXCEPTION 'Safety exception lifecycle transition is invalid';
                        END IF;
                        IF NEW.projection_version <> OLD.projection_version + 1 THEN
                            RAISE EXCEPTION 'Safety exception projection version must advance exactly once';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_risk_safety_exception_versions_protected_history
                    BEFORE UPDATE OR DELETE ON risk_safety_exception_versions
                    FOR EACH ROW EXECUTE FUNCTION protect_risk_safety_exception_version()
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX uk_authored_rule_one_active_identity
                    ON authored_rule_versions (merchant_id, rule_code)
                    WHERE lifecycle_status = 'ACTIVE'
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX uk_risk_safety_exception_one_active_identity
                    ON risk_safety_exception_versions (merchant_id, exception_code)
                    WHERE lifecycle_status = 'ACTIVE'
                    """);
        }
    }
}
