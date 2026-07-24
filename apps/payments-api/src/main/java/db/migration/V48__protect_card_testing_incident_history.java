package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V48__protect_card_testing_incident_history extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION reject_card_testing_incident_history_mutation()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'Card-testing incident history is append-only';
                        END; $$
                        """);
                for (String table : new String[] {
                        "incident_evidence_links", "card_testing_incident_resolutions"}) {
                    statement.execute("CREATE TRIGGER trg_" + table + "_immutable "
                            + "BEFORE UPDATE OR DELETE ON " + table + " FOR EACH ROW "
                            + "EXECUTE FUNCTION reject_card_testing_incident_history_mutation()");
                }
                statement.execute("""
                        CREATE FUNCTION enforce_card_testing_incident_lifecycle()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF TG_OP = 'DELETE'
                               OR OLD.id IS DISTINCT FROM NEW.id
                               OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                               OR OLD.trigger_code IS DISTINCT FROM NEW.trigger_code
                               OR OLD.source_attestation IS DISTINCT FROM NEW.source_attestation
                               OR OLD.detection_window_started_at IS DISTINCT FROM NEW.detection_window_started_at
                               OR OLD.detection_window_ended_at IS DISTINCT FROM NEW.detection_window_ended_at
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR NEW.attempt_count < OLD.attempt_count
                               OR NEW.decline_count < OLD.decline_count
                               OR NEW.distinct_card_count < OLD.distinct_card_count
                               OR NEW.distinct_bin_count < OLD.distinct_bin_count
                               OR NEW.distinct_ip_count < OLD.distinct_ip_count
                               OR NEW.first_evidence_at <> OLD.first_evidence_at
                               OR NEW.last_evidence_at < OLD.last_evidence_at
                               OR NOT (
                                    (OLD.status = 'OPEN' AND NEW.status = 'OPEN')
                                    OR (OLD.status = 'OPEN' AND NEW.status = 'RESOLVED')
                               ) THEN
                                RAISE EXCEPTION 'card-testing incident lifecycle is immutable';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_card_testing_incident_lifecycle
                        BEFORE UPDATE OR DELETE ON card_testing_incidents
                        FOR EACH ROW EXECUTE FUNCTION enforce_card_testing_incident_lifecycle()
                        """);
                statement.execute("""
                        CREATE FUNCTION validate_card_testing_incident_resolution()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE incident_row card_testing_incidents%ROWTYPE;
                        BEGIN
                            SELECT * INTO incident_row FROM card_testing_incidents
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.incident_id;
                            IF NOT FOUND OR incident_row.status <> 'RESOLVED'
                               OR incident_row.resolved_at <> NEW.created_at THEN
                                RAISE EXCEPTION 'incident resolution projection mismatch';
                            END IF;
                            RETURN NULL;
                        END; $$
                        """);
                statement.execute("""
                        CREATE CONSTRAINT TRIGGER trg_card_testing_incident_resolution_projection
                        AFTER INSERT ON card_testing_incident_resolutions
                        DEFERRABLE INITIALLY DEFERRED
                        FOR EACH ROW EXECUTE FUNCTION validate_card_testing_incident_resolution()
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("Card-testing incident protection is unsupported for " + product);
            }
        }
    }
}
