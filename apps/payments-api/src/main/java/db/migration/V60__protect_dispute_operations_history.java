package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V60__protect_dispute_operations_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_dispute_operations_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Dispute operations history is append-only';
                    END; $$
                    """);
            for (String table : new String[] {"dispute_reason_deadline_definitions",
                    "dispute_evidence_versions", "dispute_outcomes", "dispute_workflow_events"}) {
                statement.execute("CREATE TRIGGER trg_" + table + "_append_only "
                        + "BEFORE UPDATE OR DELETE ON " + table + " FOR EACH ROW "
                        + "EXECUTE FUNCTION reject_dispute_operations_history_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION protect_dispute_workflow_projection()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Dispute workflows cannot be deleted'; END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.chargeback_id <> OLD.chargeback_id OR NEW.reason_code <> OLD.reason_code
                           OR NEW.deadline_catalogue_version <> OLD.deadline_catalogue_version
                           OR NEW.due_at <> OLD.due_at OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Dispute workflow immutable identity cannot change';
                        END IF;
                        IF NEW.projection_version <> OLD.projection_version + 1 THEN
                            RAISE EXCEPTION 'Dispute workflow projection version must advance exactly once';
                        END IF;
                        IF NOT (
                            (OLD.lifecycle_status IN ('DRAFT','READY')
                                AND NEW.lifecycle_status IN ('DRAFT','READY','SUBMITTED','EXPIRED'))
                            OR (OLD.lifecycle_status = 'SUBMITTED'
                                AND NEW.lifecycle_status IN ('WON','LOST'))) THEN
                            RAISE EXCEPTION 'Dispute workflow lifecycle transition is invalid';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_dispute_workflows_protected_history
                    BEFORE UPDATE OR DELETE ON dispute_workflows
                    FOR EACH ROW EXECUTE FUNCTION protect_dispute_workflow_projection()
                    """);
        }
    }
}
