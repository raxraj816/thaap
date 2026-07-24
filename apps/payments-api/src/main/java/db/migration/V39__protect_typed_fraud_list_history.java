package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V39__protect_typed_fraud_list_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION reject_fraud_list_history_mutation()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'fraud-list history is immutable';
                        END; $$
                        """);
                for (String table : new String[] {"fraud_lists", "fraud_list_versions", "fraud_list_entries",
                        "fraud_list_lifecycle_events", "fraud_list_evaluations"}) {
                    statement.execute("CREATE TRIGGER trg_" + table + "_immutable BEFORE UPDATE OR DELETE ON "
                            + table + " FOR EACH ROW EXECUTE FUNCTION reject_fraud_list_history_mutation()");
                }
                statement.execute("""
                        CREATE FUNCTION validate_fraud_list_lifecycle_event()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE version_start TIMESTAMP(6) WITH TIME ZONE;
                        BEGIN
                            SELECT effective_from INTO version_start
                              FROM fraud_list_versions
                             WHERE merchant_id = NEW.merchant_id
                               AND id = NEW.fraud_list_version_id
                               AND fraud_list_id = NEW.fraud_list_id;
                            IF NOT FOUND OR NEW.occurred_at < version_start THEN
                                RAISE EXCEPTION 'fraud-list lifecycle time precedes its version';
                            END IF;
                            IF NEW.event_type IN ('VERSION_ACTIVATED', 'VERSION_DEACTIVATED')
                               AND NEW.lifecycle_target_id IS DISTINCT FROM NEW.fraud_list_version_id THEN
                                RAISE EXCEPTION 'fraud-list version lifecycle target mismatch';
                            END IF;
                            IF NEW.event_type = 'ENTRY_DEACTIVATED'
                               AND NEW.lifecycle_target_id IS DISTINCT FROM NEW.fraud_list_entry_id THEN
                                RAISE EXCEPTION 'fraud-list entry lifecycle target mismatch';
                            END IF;
                            IF NEW.event_type IN ('VERSION_DEACTIVATED', 'ENTRY_DEACTIVATED')
                               AND NOT EXISTS (
                                   SELECT 1 FROM fraud_list_lifecycle_events activation
                                    WHERE activation.merchant_id = NEW.merchant_id
                                      AND activation.fraud_list_version_id = NEW.fraud_list_version_id
                                      AND activation.event_type = 'VERSION_ACTIVATED'
                                      AND activation.occurred_at <= NEW.occurred_at
                               ) THEN
                                RAISE EXCEPTION 'fraud-list deactivation requires prior activation';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_fraud_list_lifecycle_semantics
                        BEFORE INSERT ON fraud_list_lifecycle_events
                        FOR EACH ROW EXECUTE FUNCTION validate_fraud_list_lifecycle_event()
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("Fraud-list history migration is unsupported for " + product);
            }
        }
    }
}
