package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Protects exact event-to-source lineage as immutable PostgreSQL history. */
public final class V84__protect_exact_fraud_event_source_lineage extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION validate_domain_event_source_lineage()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM domain_events event
                            WHERE event.merchant_id = NEW.merchant_id
                              AND event.id = NEW.domain_event_id
                              AND event.event_type = 'fraud_decision.created') THEN
                            RAISE EXCEPTION 'event source lineage requires a fraud decision event'
                                USING ERRCODE = '23514';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_domain_event_source_lineage_validate
                    BEFORE INSERT ON domain_event_source_lineage
                    FOR EACH ROW EXECUTE FUNCTION validate_domain_event_source_lineage()
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_domain_event_source_lineage_immutable
                    BEFORE UPDATE OR DELETE ON domain_event_source_lineage
                    FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                    """);
        }
    }
}
