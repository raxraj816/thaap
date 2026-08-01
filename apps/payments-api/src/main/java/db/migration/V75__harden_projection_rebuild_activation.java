package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds versioned reconciliation evidence and activation-snapshot lineage. */
public final class V75__harden_projection_rebuild_activation extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(
                connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE projection_reconciliations
                    ADD COLUMN reconciliation_attempt INTEGER NOT NULL DEFAULT 1
                    """);
            statement.execute("""
                    ALTER TABLE projection_reconciliations
                    DROP CONSTRAINT uk_projection_reconciliations_family
                    """);
            statement.execute("""
                    ALTER TABLE projection_reconciliations
                    ADD CONSTRAINT uk_projection_reconciliations_attempt_family UNIQUE
                        (projection_rebuild_id, reconciliation_attempt, invariant_family)
                    """);
            statement.execute("""
                    ALTER TABLE projection_reconciliations
                    ADD CONSTRAINT ck_projection_reconciliations_attempt CHECK
                        (reconciliation_attempt > 0)
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN current_reconciliation_attempt INTEGER NOT NULL DEFAULT 0
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN reconciliation_source_sha256 VARCHAR(64)
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD COLUMN previous_active_generation_id UUID
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD CONSTRAINT fk_projection_rebuilds_previous_generation
                    FOREIGN KEY (projection_definition_id, previous_active_generation_id)
                    REFERENCES projection_generations (projection_definition_id, id)
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD CONSTRAINT ck_projection_rebuilds_reconciliation_attempt CHECK
                        (current_reconciliation_attempt >= 0)
                    """);
            statement.execute("""
                    ALTER TABLE projection_rebuilds
                    ADD CONSTRAINT ck_projection_rebuilds_reconciliation_digest CHECK (
                        (current_reconciliation_attempt = 0
                            AND reconciliation_source_sha256 IS NULL)
                        OR (current_reconciliation_attempt > 0
                            AND LENGTH(reconciliation_source_sha256) = 64))
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_reconciliations_latest
                    ON projection_reconciliations
                        (projection_rebuild_id, reconciliation_attempt,
                         result_status, invariant_family)
                    """);
            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE projection_rebuilds
                ADD CONSTRAINT ck_projection_rebuilds_reconciliation_digest_lower_hex
                CHECK (reconciliation_source_sha256 IS NULL
                    OR reconciliation_source_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_projection_rebuild()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR NEW.id <> OLD.id
                       OR NEW.projection_definition_id <> OLD.projection_definition_id
                       OR NEW.projection_generation_id <> OLD.projection_generation_id
                       OR NEW.cutoff_manifest_id <> OLD.cutoff_manifest_id
                       OR NEW.requested_by_operator_id <> OLD.requested_by_operator_id
                       OR NEW.handler_version <> OLD.handler_version
                       OR NEW.expected_input_count <> OLD.expected_input_count
                       OR NEW.created_at <> OLD.created_at
                       OR NEW.fencing_epoch < OLD.fencing_epoch
                       OR NEW.processed_input_count < OLD.processed_input_count
                       OR NEW.current_reconciliation_attempt
                            < OLD.current_reconciliation_attempt
                       OR NEW.current_reconciliation_attempt
                            > OLD.current_reconciliation_attempt + 1
                       OR (NEW.current_reconciliation_attempt =
                                OLD.current_reconciliation_attempt
                           AND NEW.reconciliation_source_sha256 IS DISTINCT FROM
                                OLD.reconciliation_source_sha256)
                       OR (OLD.previous_active_generation_id IS NOT NULL
                           AND NEW.previous_active_generation_id IS DISTINCT FROM
                                OLD.previous_active_generation_id)
                       OR (OLD.previous_active_generation_id IS NULL
                           AND NEW.previous_active_generation_id IS NOT NULL
                           AND NOT (OLD.lifecycle_status = 'RECONCILED'
                               AND NEW.lifecycle_status = 'ACTIVATED'))
                       OR NOT (
                           OLD.lifecycle_status = NEW.lifecycle_status
                           OR (OLD.lifecycle_status = 'QUEUED'
                               AND NEW.lifecycle_status IN
                                   ('RUNNING', 'CANCELED', 'FAILED'))
                           OR (OLD.lifecycle_status = 'RUNNING'
                               AND NEW.lifecycle_status IN
                                   ('RUNNING', 'CATCHING_UP', 'CANCELED', 'FAILED'))
                           OR (OLD.lifecycle_status = 'CATCHING_UP'
                               AND NEW.lifecycle_status IN
                                   ('CATCHING_UP', 'RECONCILED', 'CANCELED', 'FAILED'))
                           OR (OLD.lifecycle_status = 'RECONCILED'
                               AND NEW.lifecycle_status = 'ACTIVATED')
                       ) THEN
                        RAISE EXCEPTION 'projection rebuild mutation is invalid'
                            USING ERRCODE = '55000';
                    END IF;
                    IF NEW.fencing_epoch > OLD.fencing_epoch
                       AND NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token THEN
                        RAISE EXCEPTION 'new rebuild fence requires a new lease token'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION require_reconciled_projection_activation()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                DECLARE matching_rebuilds BIGINT;
                DECLARE retained_by_rebuilds BIGINT;
                DECLARE passing_families BIGINT;
                DECLARE missing_or_mismatched_streams BIGINT;
                DECLARE extra_streams BIGINT;
                BEGIN
                    IF NEW.active_generation_id IS NOT DISTINCT FROM OLD.active_generation_id THEN
                        RETURN NEW;
                    END IF;
                    SELECT COUNT(*) INTO matching_rebuilds
                    FROM projection_rebuilds
                    WHERE projection_definition_id = NEW.id
                      AND projection_generation_id = NEW.active_generation_id
                      AND lifecycle_status IN ('RECONCILED', 'ACTIVATED')
                      AND current_reconciliation_attempt > 0;
                    SELECT COUNT(*) INTO passing_families
                    FROM projection_reconciliations reconciliation
                    JOIN projection_rebuilds rebuild
                      ON rebuild.id = reconciliation.projection_rebuild_id
                     AND rebuild.current_reconciliation_attempt =
                         reconciliation.reconciliation_attempt
                    WHERE rebuild.projection_definition_id = NEW.id
                      AND rebuild.projection_generation_id = NEW.active_generation_id
                      AND reconciliation.result_status = 'PASS';
                    SELECT COUNT(*) INTO retained_by_rebuilds
                    FROM projection_rebuilds rebuild
                    WHERE rebuild.projection_definition_id = NEW.id
                      AND rebuild.previous_active_generation_id = NEW.active_generation_id
                      AND rebuild.lifecycle_status = 'ACTIVATED'
                      AND rebuild.current_reconciliation_attempt > 0;
                    SELECT COUNT(*) INTO missing_or_mismatched_streams
                    FROM event_stream_heads head
                    LEFT JOIN domain_events terminal
                      ON terminal.merchant_id = head.merchant_id
                     AND terminal.stream_type = head.stream_type
                     AND terminal.stream_id = head.stream_id
                     AND terminal.stream_version = head.current_version
                    LEFT JOIN projection_stream_positions position
                      ON position.merchant_id = head.merchant_id
                     AND position.projection_generation_id = NEW.active_generation_id
                     AND position.stream_type = head.stream_type
                     AND position.stream_id = head.stream_id
                    WHERE head.current_version > 0
                      AND (position.last_stream_version IS DISTINCT FROM head.current_version
                        OR position.last_domain_event_id IS DISTINCT FROM terminal.id
                        OR position.last_event_record_sha256 IS DISTINCT FROM
                            terminal.event_record_sha256);
                    SELECT COUNT(*) INTO extra_streams
                    FROM projection_stream_positions position
                    LEFT JOIN event_stream_heads head
                      ON head.merchant_id = position.merchant_id
                     AND head.stream_type = position.stream_type
                     AND head.stream_id = position.stream_id
                    WHERE position.projection_generation_id = NEW.active_generation_id
                      AND (head.stream_id IS NULL OR head.current_version = 0);
                    IF NOT ((matching_rebuilds = 1 AND passing_families = 5)
                            OR retained_by_rebuilds >= 1)
                       OR missing_or_mismatched_streams <> 0
                       OR extra_streams <> 0 THEN
                        RAISE EXCEPTION 'projection activation requires current complete reconciliation'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
    }
}
