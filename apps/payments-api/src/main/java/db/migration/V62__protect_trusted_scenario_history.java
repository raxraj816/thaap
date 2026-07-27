package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V62__protect_trusted_scenario_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION protect_developer_scenario_run()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Developer scenario runs cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.scenario_type <> OLD.scenario_type
                           OR NEW.catalogue_version <> OLD.catalogue_version
                           OR NEW.seed_digest <> OLD.seed_digest
                           OR NEW.specification_json <> OLD.specification_json
                           OR NEW.specification_digest <> OLD.specification_digest
                           OR NEW.total_steps <> OLD.total_steps
                           OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Developer scenario run identity is immutable';
                        END IF;
                        IF NEW.next_step_index < OLD.next_step_index OR NEW.lease_epoch < OLD.lease_epoch THEN
                            RAISE EXCEPTION 'Developer scenario progress is monotonic';
                        END IF;
                        IF OLD.lifecycle_status IN ('SUCCEEDED', 'FAILED') THEN
                            RAISE EXCEPTION 'Terminal developer scenario runs are immutable';
                        END IF;
                        IF OLD.lifecycle_status = 'CREATED' AND NEW.lifecycle_status NOT IN ('CREATED', 'RUNNING', 'FAILED')
                           OR OLD.lifecycle_status = 'RUNNING' AND NEW.lifecycle_status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED') THEN
                            RAISE EXCEPTION 'Invalid developer scenario lifecycle transition';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_developer_scenario_runs_protected
                    BEFORE UPDATE OR DELETE ON developer_scenario_runs
                    FOR EACH ROW EXECUTE FUNCTION protect_developer_scenario_run()
                    """);
            statement.execute("""
                    CREATE FUNCTION protect_developer_scenario_step()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Developer scenario steps cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.scenario_run_id <> OLD.scenario_run_id
                           OR NEW.step_index <> OLD.step_index OR NEW.step_key <> OLD.step_key
                           OR NEW.operation <> OLD.operation OR NEW.fixture_id IS DISTINCT FROM OLD.fixture_id
                           OR NEW.amount IS DISTINCT FROM OLD.amount
                           OR NEW.expected_outcome_code IS DISTINCT FROM OLD.expected_outcome_code
                           OR NEW.idempotency_key <> OLD.idempotency_key OR NEW.resource_type <> OLD.resource_type
                           OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Developer scenario step identity is immutable';
                        END IF;
                        IF OLD.lifecycle_status = 'COMPLETED'
                           OR NEW.lifecycle_status <> 'COMPLETED'
                           OR NEW.resource_id IS NULL OR NEW.completed_at IS NULL THEN
                            RAISE EXCEPTION 'Invalid developer scenario step transition';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_developer_scenario_steps_protected
                    BEFORE UPDATE OR DELETE ON developer_scenario_steps
                    FOR EACH ROW EXECUTE FUNCTION protect_developer_scenario_step()
                    """);
            statement.execute("""
                    CREATE FUNCTION reject_developer_scenario_event_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Developer scenario events are append-only';
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_developer_scenario_events_append_only
                    BEFORE UPDATE OR DELETE ON developer_scenario_events
                    FOR EACH ROW EXECUTE FUNCTION reject_developer_scenario_event_mutation()
                    """);
        }
    }
}
