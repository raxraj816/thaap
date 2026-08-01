package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds durable, append-only evidence for globally bounded projection catch-up cycles. */
public final class V81__add_bounded_projection_catchup_evidence extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(
                connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN catchup_cycle_count INTEGER NOT NULL DEFAULT 0
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN catchup_input_count BIGINT NOT NULL DEFAULT 0
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN catchup_remaining_count BIGINT NOT NULL DEFAULT 0
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN latest_catchup_source_sha256 VARCHAR(64)
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD CONSTRAINT ck_projection_rebuilds_catchup_counts CHECK (
                        catchup_cycle_count >= 0 AND catchup_input_count >= 0
                        AND catchup_remaining_count >= 0
                        AND ((catchup_cycle_count = 0
                                AND latest_catchup_source_sha256 IS NULL)
                            OR (catchup_cycle_count > 0
                                AND LENGTH(latest_catchup_source_sha256) = 64)))
                    """);
            statement.execute("""
                    CREATE TABLE projection_catchup_cycles (
                        id UUID NOT NULL,
                        projection_rebuild_id UUID NOT NULL,
                        cycle_number INTEGER NOT NULL,
                        source_snapshot_sha256 VARCHAR(64) NOT NULL,
                        source_event_count BIGINT NOT NULL,
                        applied_input_count INTEGER NOT NULL,
                        remaining_input_count BIGINT NOT NULL,
                        first_domain_event_id UUID,
                        last_domain_event_id UUID,
                        actor_operator_id UUID NOT NULL,
                        occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_catchup_cycles PRIMARY KEY (id),
                        CONSTRAINT uk_projection_catchup_cycles_number UNIQUE
                            (projection_rebuild_id, cycle_number),
                        CONSTRAINT fk_projection_catchup_cycles_rebuild
                            FOREIGN KEY (projection_rebuild_id)
                            REFERENCES projection_rebuilds (id),
                        CONSTRAINT fk_projection_catchup_cycles_operator
                            FOREIGN KEY (actor_operator_id)
                            REFERENCES platform_operators (id),
                        CONSTRAINT ck_projection_catchup_cycles_counts CHECK (
                            cycle_number > 0 AND source_event_count >= 0
                            AND applied_input_count BETWEEN 0 AND 100
                            AND remaining_input_count >= 0),
                        CONSTRAINT ck_projection_catchup_cycles_boundary CHECK (
                            (applied_input_count = 0 AND first_domain_event_id IS NULL
                                AND last_domain_event_id IS NULL)
                            OR (applied_input_count > 0 AND first_domain_event_id IS NOT NULL
                                AND last_domain_event_id IS NOT NULL))
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_catchup_cycles_rebuild
                    ON projection_catchup_cycles (projection_rebuild_id, cycle_number)
                    """);
            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE projection_rebuilds
                ADD CONSTRAINT ck_projection_rebuilds_catchup_digest_lower_hex CHECK (
                    latest_catchup_source_sha256 IS NULL
                    OR latest_catchup_source_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE projection_catchup_cycles
                ADD CONSTRAINT ck_projection_catchup_cycles_digest_lower_hex CHECK (
                    source_snapshot_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_catchup_cycles_immutable
                BEFORE UPDATE OR DELETE ON projection_catchup_cycles
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_projection_catchup_progress()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF NEW.catchup_cycle_count < OLD.catchup_cycle_count
                       OR NEW.catchup_cycle_count > OLD.catchup_cycle_count + 1
                       OR NEW.catchup_input_count < OLD.catchup_input_count
                       OR (NEW.catchup_cycle_count = OLD.catchup_cycle_count
                           AND (NEW.catchup_input_count <> OLD.catchup_input_count
                               OR NEW.catchup_remaining_count <>
                                  OLD.catchup_remaining_count
                               OR NEW.latest_catchup_source_sha256 IS DISTINCT FROM
                                  OLD.latest_catchup_source_sha256))
                       OR (NEW.catchup_cycle_count = OLD.catchup_cycle_count + 1
                           AND (NEW.catchup_input_count - OLD.catchup_input_count
                                   NOT BETWEEN 0 AND 100
                               OR NEW.latest_catchup_source_sha256 IS NULL)) THEN
                        RAISE EXCEPTION 'projection catch-up progress mutation is invalid'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_rebuilds_catchup_progress
                BEFORE UPDATE ON projection_rebuilds
                FOR EACH ROW EXECUTE FUNCTION protect_projection_catchup_progress()
                """);
    }
}
