package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V55__protect_historical_risk_simulation_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_risk_simulation_run_invalid_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Risk simulation runs cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id
                           OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.created_by_operator_id <> OLD.created_by_operator_id
                           OR NEW.candidate_type <> OLD.candidate_type
                           OR NEW.candidate_version_id <> OLD.candidate_version_id
                           OR NEW.candidate_schema_version <> OLD.candidate_schema_version
                           OR NEW.candidate_json <> OLD.candidate_json
                           OR NEW.candidate_digest <> OLD.candidate_digest
                           OR NEW.cohort_start <> OLD.cohort_start
                           OR NEW.cohort_end <> OLD.cohort_end
                           OR NEW.as_of <> OLD.as_of
                           OR NEW.traffic_origin_policy <> OLD.traffic_origin_policy
                           OR NEW.label_cutoff <> OLD.label_cutoff
                           OR NEW.label_resolution_policy <> OLD.label_resolution_policy
                           OR NEW.evaluator_version <> OLD.evaluator_version
                           OR NEW.feature_catalogue_version <> OLD.feature_catalogue_version
                           OR NEW.rule_catalogue_version <> OLD.rule_catalogue_version
                           OR NEW.taxonomy_version <> OLD.taxonomy_version
                           OR NEW.query_digest <> OLD.query_digest
                           OR NEW.execution_token <> OLD.execution_token
                           OR NEW.created_at <> OLD.created_at
                           OR NEW.queued_at <> OLD.queued_at THEN
                            RAISE EXCEPTION 'Risk simulation immutable run identity cannot change';
                        END IF;
                        IF OLD.lifecycle_status IN ('SUCCEEDED', 'FAILED', 'CANCELED') THEN
                            RAISE EXCEPTION 'Risk simulation terminal state cannot change';
                        END IF;
                        IF NOT (
                            (OLD.lifecycle_status = 'QUEUED' AND NEW.lifecycle_status IN ('QUEUED', 'RUNNING', 'CANCELED'))
                            OR (OLD.lifecycle_status = 'RUNNING' AND NEW.lifecycle_status IN
                                ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'))
                        ) THEN
                            RAISE EXCEPTION 'Risk simulation lifecycle transition is invalid';
                        END IF;
                        IF NEW.projection_version <> OLD.projection_version + 1 THEN
                            RAISE EXCEPTION 'Risk simulation projection version must advance exactly once';
                        END IF;
                        IF NEW.processed_count < OLD.processed_count
                           OR NEW.eligible_count < OLD.eligible_count
                           OR NEW.excluded_count < OLD.excluded_count THEN
                            RAISE EXCEPTION 'Risk simulation progress cannot decrease';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_risk_simulation_runs_protected_history
                    BEFORE UPDATE OR DELETE ON risk_simulation_runs
                    FOR EACH ROW EXECUTE FUNCTION reject_risk_simulation_run_invalid_mutation()
                    """);
            statement.execute("""
                    CREATE FUNCTION reject_risk_simulation_append_only_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Risk simulation evidence is append-only';
                    END; $$
                    """);
            for (String table : new String[] {"risk_simulation_events", "risk_simulation_results",
                    "risk_simulation_reports", "risk_simulation_segments"}) {
                statement.execute("CREATE TRIGGER trg_" + table + "_append_only "
                        + "BEFORE UPDATE OR DELETE ON " + table + " FOR EACH ROW "
                        + "EXECUTE FUNCTION reject_risk_simulation_append_only_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION validate_risk_simulation_terminal_truth()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE terminal_events BIGINT;
                    DECLARE reports BIGINT;
                    BEGIN
                        IF NEW.lifecycle_status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED') THEN
                            RETURN NEW;
                        END IF;
                        SELECT COUNT(*) INTO terminal_events
                          FROM risk_simulation_events event
                         WHERE event.merchant_id = NEW.merchant_id
                           AND event.simulation_run_id = NEW.id
                           AND event.event_type = NEW.lifecycle_status;
                        SELECT COUNT(*) INTO reports
                          FROM risk_simulation_reports report
                         WHERE report.merchant_id = NEW.merchant_id
                           AND report.simulation_run_id = NEW.id;
                        IF terminal_events <> 1
                           OR (NEW.lifecycle_status = 'SUCCEEDED' AND reports <> 1)
                           OR (NEW.lifecycle_status <> 'SUCCEEDED' AND reports <> 0) THEN
                            RAISE EXCEPTION 'Risk simulation terminal evidence does not match run state';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE CONSTRAINT TRIGGER trg_risk_simulation_terminal_semantics
                    AFTER UPDATE ON risk_simulation_runs
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION validate_risk_simulation_terminal_truth()
                    """);
        }
    }
}
