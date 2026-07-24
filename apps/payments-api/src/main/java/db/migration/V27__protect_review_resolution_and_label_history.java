package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V27__protect_review_resolution_and_label_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("DROP TRIGGER trg_manual_review_history ON manual_review_cases");
                statement.execute("DROP FUNCTION enforce_manual_review_history()");
                statement.execute("""
                        CREATE FUNCTION enforce_manual_review_history()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF TG_OP = 'DELETE' THEN
                                RAISE EXCEPTION 'manual review history is immutable';
                            END IF;
                            IF OLD.id IS DISTINCT FROM NEW.id
                               OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                               OR OLD.payment_intent_id IS DISTINCT FROM NEW.payment_intent_id
                               OR OLD.payment_attempt_id IS DISTINCT FROM NEW.payment_attempt_id
                               OR OLD.fraud_decision_id IS DISTINCT FROM NEW.fraud_decision_id
                               OR OLD.decision_action IS DISTINCT FROM NEW.decision_action
                               OR OLD.reason_codes IS DISTINCT FROM NEW.reason_codes
                               OR OLD.traffic_origin IS DISTINCT FROM NEW.traffic_origin
                               OR OLD.review_stage IS DISTINCT FROM NEW.review_stage
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR NEW.assignment_version < OLD.assignment_version
                               OR NOT (
                                    (OLD.status = 'OPEN' AND NEW.status IN ('ASSIGNED', 'RESOLVED', 'CANCELED'))
                                 OR (OLD.status = 'ASSIGNED' AND NEW.status IN ('OPEN', 'ASSIGNED', 'RESOLVED', 'CANCELED'))
                               ) THEN
                                RAISE EXCEPTION 'manual review history is immutable';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_manual_review_history
                        BEFORE UPDATE OR DELETE ON manual_review_cases
                        FOR EACH ROW EXECUTE FUNCTION enforce_manual_review_history()
                        """);
                statement.execute("""
                        CREATE FUNCTION reject_review_and_label_history_mutation()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'Review and label history is append-only';
                        END; $$
                        """);
                for (String table : new String[] {
                        "review_assignment_events", "review_annotations",
                        "review_resolutions", "fraud_labels"}) {
                    statement.execute("CREATE TRIGGER trg_" + table + "_immutable "
                            + "BEFORE UPDATE OR DELETE ON " + table + " FOR EACH ROW "
                            + "EXECUTE FUNCTION reject_review_and_label_history_mutation()");
                }
                statement.execute("""
                        CREATE FUNCTION enforce_risk_operator_lifecycle()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF TG_OP = 'DELETE'
                               OR OLD.id IS DISTINCT FROM NEW.id
                               OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                               OR OLD.operator_code IS DISTINCT FROM NEW.operator_code
                               OR OLD.role IS DISTINCT FROM NEW.role
                               OR OLD.created_by_api_key_prefix IS DISTINCT FROM NEW.created_by_api_key_prefix
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR OLD.status <> 'ACTIVE' OR NEW.status <> 'DISABLED'
                               OR OLD.lifecycle_epoch <> 1 OR NEW.lifecycle_epoch <> 2
                               OR NEW.disabled_at IS NULL THEN
                                RAISE EXCEPTION 'risk operator lifecycle is immutable';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_risk_operator_lifecycle
                        BEFORE UPDATE OR DELETE ON risk_operators
                        FOR EACH ROW EXECUTE FUNCTION enforce_risk_operator_lifecycle()
                        """);
                statement.execute("""
                        CREATE FUNCTION enforce_risk_credential_lifecycle()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF TG_OP = 'DELETE'
                               OR OLD.id IS DISTINCT FROM NEW.id
                               OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                               OR OLD.risk_operator_id IS DISTINCT FROM NEW.risk_operator_id
                               OR OLD.secret_prefix IS DISTINCT FROM NEW.secret_prefix
                               OR OLD.secret_sha256 IS DISTINCT FROM NEW.secret_sha256
                               OR OLD.secret_identity_hmac IS DISTINCT FROM NEW.secret_identity_hmac
                               OR OLD.secret_hmac_key_version IS DISTINCT FROM NEW.secret_hmac_key_version
                               OR OLD.creation_identity_hmac IS DISTINCT FROM NEW.creation_identity_hmac
                               OR OLD.creation_request_hash IS DISTINCT FROM NEW.creation_request_hash
                               OR OLD.creation_hash_key_version IS DISTINCT FROM NEW.creation_hash_key_version
                               OR OLD.replay_ciphertext IS DISTINCT FROM NEW.replay_ciphertext
                               OR OLD.replay_nonce IS DISTINCT FROM NEW.replay_nonce
                               OR OLD.replay_key_version IS DISTINCT FROM NEW.replay_key_version
                               OR OLD.replay_expires_at IS DISTINCT FROM NEW.replay_expires_at
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR OLD.disabled_at IS NOT NULL OR NEW.disabled_at IS NULL THEN
                                RAISE EXCEPTION 'risk operator credential lifecycle is immutable';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_risk_credential_lifecycle
                        BEFORE UPDATE OR DELETE ON risk_operator_credentials
                        FOR EACH ROW EXECUTE FUNCTION enforce_risk_credential_lifecycle()
                        """);
                statement.execute("""
                        CREATE FUNCTION validate_review_resolution_lineage()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE review_row manual_review_cases%ROWTYPE;
                        BEGIN
                            SELECT * INTO review_row FROM manual_review_cases
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.manual_review_case_id;
                            IF NOT FOUND
                               OR review_row.payment_intent_id <> NEW.payment_intent_id
                               OR review_row.payment_attempt_id <> NEW.payment_attempt_id
                               OR review_row.fraud_decision_id <> NEW.fraud_decision_id
                               OR review_row.review_stage <> NEW.review_stage THEN
                                RAISE EXCEPTION 'review resolution lineage mismatch';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_review_resolution_lineage
                        BEFORE INSERT ON review_resolutions
                        FOR EACH ROW EXECUTE FUNCTION validate_review_resolution_lineage()
                        """);
                statement.execute("""
                        CREATE FUNCTION validate_fraud_label_lineage()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE resolution_row review_resolutions%ROWTYPE;
                        DECLARE prior_row fraud_labels%ROWTYPE;
                        BEGIN
                            SELECT * INTO resolution_row FROM review_resolutions
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.source_review_resolution_id;
                            IF NOT FOUND
                               OR resolution_row.payment_intent_id <> NEW.payment_intent_id
                               OR resolution_row.payment_attempt_id <> NEW.payment_attempt_id
                               OR resolution_row.fraud_decision_id <> NEW.fraud_decision_id
                               OR (SELECT traffic_origin FROM manual_review_cases
                                   WHERE merchant_id = NEW.merchant_id
                                     AND id = resolution_row.manual_review_case_id) <> NEW.traffic_origin
                               OR resolution_row.outcome <> 'CANCEL_AND_REPORT_FRAUD'
                               OR resolution_row.classification <> 'CONFIRMED_FRAUD'
                               OR resolution_row.fraud_type <> NEW.fraud_type
                               OR resolution_row.reason_code <> NEW.reason_code THEN
                                RAISE EXCEPTION 'fraud label source lineage mismatch';
                            END IF;
                            IF NEW.supersedes_label_id IS NOT NULL THEN
                                SELECT * INTO prior_row FROM fraud_labels
                                WHERE merchant_id = NEW.merchant_id AND id = NEW.supersedes_label_id;
                                IF NOT FOUND
                                   OR prior_row.payment_intent_id <> NEW.payment_intent_id
                                   OR prior_row.payment_attempt_id <> NEW.payment_attempt_id
                                   OR prior_row.fraud_decision_id <> NEW.fraud_decision_id
                                   OR NEW.supersedes_label_id = NEW.id THEN
                                    RAISE EXCEPTION 'fraud label supersession lineage mismatch';
                                END IF;
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_fraud_label_lineage
                        BEFORE INSERT ON fraud_labels
                        FOR EACH ROW EXECUTE FUNCTION validate_fraud_label_lineage()
                        """);
                statement.execute("""
                        CREATE FUNCTION validate_manual_review_projection()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE assignment_row review_assignment_events%ROWTYPE;
                        DECLARE resolution_count BIGINT;
                        BEGIN
                            IF NEW.status IN ('OPEN', 'ASSIGNED') AND NEW.assignment_version > 0 THEN
                                SELECT * INTO assignment_row FROM review_assignment_events
                                WHERE merchant_id = NEW.merchant_id
                                  AND manual_review_case_id = NEW.id
                                  AND assignment_version = NEW.assignment_version;
                                IF NOT FOUND
                                   OR (NEW.status = 'OPEN' AND (
                                        assignment_row.event_type <> 'RELEASED'
                                        OR assignment_row.to_operator_id IS NOT NULL))
                                   OR (NEW.status = 'ASSIGNED' AND (
                                        assignment_row.event_type NOT IN ('ASSIGNED', 'REASSIGNED')
                                        OR assignment_row.to_operator_id <> NEW.assigned_operator_id)) THEN
                                    RAISE EXCEPTION 'manual review assignment projection has no matching event';
                                END IF;
                            END IF;
                            IF NEW.status = 'RESOLVED' THEN
                                SELECT COUNT(*) INTO resolution_count FROM review_resolutions
                                WHERE merchant_id = NEW.merchant_id
                                  AND manual_review_case_id = NEW.id;
                                IF resolution_count <> 1 THEN
                                    RAISE EXCEPTION 'resolved review has no unique resolution';
                                END IF;
                            END IF;
                            RETURN NULL;
                        END; $$
                        """);
                statement.execute("""
                        CREATE CONSTRAINT TRIGGER trg_manual_review_projection
                        AFTER INSERT OR UPDATE ON manual_review_cases
                        DEFERRABLE INITIALLY DEFERRED
                        FOR EACH ROW EXECUTE FUNCTION validate_manual_review_projection()
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("Review and label lineage protection is unsupported for " + product);
            }
        }
    }
}
