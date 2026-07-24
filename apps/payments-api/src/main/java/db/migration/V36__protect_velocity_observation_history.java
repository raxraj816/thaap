package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V36__protect_velocity_observation_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE OR REPLACE FUNCTION protect_velocity_observation_history()
                        RETURNS trigger AS $$
                        BEGIN
                            IF TG_OP = 'DELETE' THEN
                                RAISE EXCEPTION 'velocity observation history is immutable';
                            END IF;
                            IF OLD.fraud_decision_id IS NULL
                                AND NEW.fraud_decision_id IS NOT NULL
                                AND OLD.id IS NOT DISTINCT FROM NEW.id
                                AND OLD.merchant_id IS NOT DISTINCT FROM NEW.merchant_id
                                AND OLD.payment_intent_id IS NOT DISTINCT FROM NEW.payment_intent_id
                                AND OLD.payment_attempt_id IS NOT DISTINCT FROM NEW.payment_attempt_id
                                AND OLD.dimension IS NOT DISTINCT FROM NEW.dimension
                                AND OLD.window_ms IS NOT DISTINCT FROM NEW.window_ms
                                AND OLD.availability IS NOT DISTINCT FROM NEW.availability
                                AND OLD.other_attempt_count IS NOT DISTINCT FROM NEW.other_attempt_count
                                AND OLD.redis_time_ms IS NOT DISTINCT FROM NEW.redis_time_ms
                                AND OLD.observed_at IS NOT DISTINCT FROM NEW.observed_at
                                AND OLD.script_version IS NOT DISTINCT FROM NEW.script_version
                                AND OLD.execution_mode IS NOT DISTINCT FROM NEW.execution_mode
                                AND OLD.failure_cause IS NOT DISTINCT FROM NEW.failure_cause
                                AND OLD.identity_key_version IS NOT DISTINCT FROM NEW.identity_key_version
                                AND OLD.traffic_origin IS NOT DISTINCT FROM NEW.traffic_origin
                                AND OLD.created_at IS NOT DISTINCT FROM NEW.created_at THEN
                                RETURN NEW;
                            END IF;
                            RAISE EXCEPTION 'velocity observation history is immutable';
                        END;
                        $$ LANGUAGE plpgsql
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_velocity_observations_immutable
                        BEFORE UPDATE OR DELETE ON velocity_observations
                        FOR EACH ROW EXECUTE FUNCTION protect_velocity_observation_history()
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("Velocity history migration is unsupported for " + product);
            }
        }
    }
}
