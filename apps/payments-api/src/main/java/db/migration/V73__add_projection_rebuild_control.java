package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds fenced rebuild execution, append-only evidence, and reconciliation guards. */
public final class V73__add_projection_rebuild_control extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE projection_rebuilds (
                        id UUID NOT NULL,
                        projection_definition_id UUID NOT NULL,
                        projection_generation_id UUID NOT NULL,
                        cutoff_manifest_id UUID NOT NULL,
                        requested_by_operator_id UUID NOT NULL,
                        handler_version VARCHAR(32) NOT NULL,
                        lifecycle_status VARCHAR(24) NOT NULL,
                        lease_owner VARCHAR(128),
                        lease_token UUID,
                        lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
                        fencing_epoch BIGINT NOT NULL,
                        processed_input_count BIGINT NOT NULL,
                        expected_input_count BIGINT NOT NULL,
                        last_checkpoint_key VARCHAR(256),
                        failure_code VARCHAR(48),
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        completed_at TIMESTAMP(6) WITH TIME ZONE,
                        CONSTRAINT pk_projection_rebuilds PRIMARY KEY (id),
                        CONSTRAINT uk_projection_rebuilds_generation UNIQUE
                            (projection_generation_id),
                        CONSTRAINT uk_projection_rebuilds_stable_identity UNIQUE
                            (projection_definition_id, cutoff_manifest_id, handler_version),
                        CONSTRAINT fk_projection_rebuilds_definition
                            FOREIGN KEY (projection_definition_id)
                            REFERENCES projection_definitions (id),
                        CONSTRAINT fk_projection_rebuilds_generation
                            FOREIGN KEY (projection_definition_id, projection_generation_id)
                            REFERENCES projection_generations (projection_definition_id, id),
                        CONSTRAINT fk_projection_rebuilds_cutoff
                            FOREIGN KEY (cutoff_manifest_id)
                            REFERENCES projection_cutoff_manifests (id),
                        CONSTRAINT fk_projection_rebuilds_operator
                            FOREIGN KEY (requested_by_operator_id)
                            REFERENCES platform_operators (id),
                        CONSTRAINT ck_projection_rebuilds_status CHECK (lifecycle_status IN
                            ('QUEUED', 'RUNNING', 'CATCHING_UP', 'RECONCILED',
                             'ACTIVATED', 'CANCELED', 'FAILED')),
                        CONSTRAINT ck_projection_rebuilds_lease CHECK (
                            (lifecycle_status IN ('RUNNING', 'CATCHING_UP')
                                AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                                AND lease_expires_at IS NOT NULL)
                            OR (lifecycle_status NOT IN ('RUNNING', 'CATCHING_UP')
                                AND lease_owner IS NULL AND lease_token IS NULL
                                AND lease_expires_at IS NULL)),
                        CONSTRAINT ck_projection_rebuilds_counts CHECK (
                            fencing_epoch >= 0 AND processed_input_count >= 0
                            AND expected_input_count >= processed_input_count),
                        CONSTRAINT ck_projection_rebuilds_failure CHECK (
                            (lifecycle_status = 'FAILED' AND failure_code IS NOT NULL)
                            OR (lifecycle_status <> 'FAILED' AND failure_code IS NULL)),
                        CONSTRAINT ck_projection_rebuilds_time CHECK (
                            updated_at >= created_at
                            AND (lease_expires_at IS NULL OR lease_expires_at > updated_at)
                            AND (completed_at IS NULL OR completed_at >= created_at))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_rebuild_events (
                        id UUID NOT NULL,
                        projection_rebuild_id UUID NOT NULL,
                        event_sequence BIGINT NOT NULL,
                        event_type VARCHAR(32) NOT NULL,
                        fencing_epoch BIGINT NOT NULL,
                        actor_operator_id UUID,
                        safe_reason_code VARCHAR(48),
                        processed_input_count BIGINT NOT NULL,
                        occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_rebuild_events PRIMARY KEY (id),
                        CONSTRAINT uk_projection_rebuild_events_sequence UNIQUE
                            (projection_rebuild_id, event_sequence),
                        CONSTRAINT fk_projection_rebuild_events_rebuild
                            FOREIGN KEY (projection_rebuild_id)
                            REFERENCES projection_rebuilds (id),
                        CONSTRAINT fk_projection_rebuild_events_operator
                            FOREIGN KEY (actor_operator_id)
                            REFERENCES platform_operators (id),
                        CONSTRAINT ck_projection_rebuild_events_type CHECK (event_type IN
                            ('QUEUED', 'LEASED', 'RESUMED', 'CHUNK_COMMITTED',
                             'CATCH_UP_STARTED', 'RECONCILED', 'ACTIVATED',
                             'CANCELED', 'FAILED', 'LEASE_RECOVERED', 'ROLLED_BACK')),
                        CONSTRAINT ck_projection_rebuild_events_counts CHECK (
                            event_sequence > 0 AND fencing_epoch >= 0
                            AND processed_input_count >= 0)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_reconciliations (
                        id UUID NOT NULL,
                        projection_rebuild_id UUID NOT NULL,
                        invariant_family VARCHAR(32) NOT NULL,
                        result_status VARCHAR(8) NOT NULL,
                        expected_count BIGINT,
                        actual_count BIGINT,
                        expected_sha256 VARCHAR(64),
                        actual_sha256 VARCHAR(64),
                        safe_detail_code VARCHAR(48),
                        reconciled_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_reconciliations PRIMARY KEY (id),
                        CONSTRAINT uk_projection_reconciliations_family UNIQUE
                            (projection_rebuild_id, invariant_family),
                        CONSTRAINT fk_projection_reconciliations_rebuild
                            FOREIGN KEY (projection_rebuild_id)
                            REFERENCES projection_rebuilds (id),
                        CONSTRAINT ck_projection_reconciliations_family CHECK (invariant_family IN
                            ('INPUT_COUNT', 'STREAM_COVERAGE', 'PROJECTION_ROW_COUNT',
                             'CONTENT_DIGEST', 'BUSINESS_INVARIANT')),
                        CONSTRAINT ck_projection_reconciliations_status CHECK
                            (result_status IN ('PASS', 'FAIL')),
                        CONSTRAINT ck_projection_reconciliations_counts CHECK (
                            (expected_count IS NULL AND actual_count IS NULL)
                            OR (expected_count >= 0 AND actual_count >= 0)),
                        CONSTRAINT ck_projection_reconciliations_digests CHECK (
                            (expected_sha256 IS NULL AND actual_sha256 IS NULL)
                            OR (LENGTH(expected_sha256) = 64 AND LENGTH(actual_sha256) = 64)),
                        CONSTRAINT ck_projection_reconciliations_evidence CHECK (
                            expected_count IS NOT NULL OR expected_sha256 IS NOT NULL)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_rebuilds_worker
                    ON projection_rebuilds
                        (lifecycle_status, lease_expires_at, created_at, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_rebuild_events_rebuild
                    ON projection_rebuild_events
                        (projection_rebuild_id, event_sequence)
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_reconciliations_rebuild
                    ON projection_reconciliations
                        (projection_rebuild_id, result_status, invariant_family)
                    """);
            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE projection_reconciliations
                ADD CONSTRAINT ck_projection_reconciliations_digest_lower_hex
                CHECK ((expected_sha256 IS NULL AND actual_sha256 IS NULL)
                    OR (expected_sha256 ~ '^[0-9a-f]{64}$'
                        AND actual_sha256 ~ '^[0-9a-f]{64}$'))
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
                CREATE TRIGGER trg_projection_rebuilds_protected
                BEFORE UPDATE OR DELETE ON projection_rebuilds
                FOR EACH ROW EXECUTE FUNCTION protect_projection_rebuild()
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_rebuild_events_immutable
                BEFORE UPDATE OR DELETE ON projection_rebuild_events
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_reconciliations_immutable
                BEFORE UPDATE OR DELETE ON projection_reconciliations
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION require_reconciled_projection_activation()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                DECLARE matching_rebuilds BIGINT;
                DECLARE passing_families BIGINT;
                BEGIN
                    IF NEW.active_generation_id IS NOT DISTINCT FROM OLD.active_generation_id THEN
                        RETURN NEW;
                    END IF;
                    SELECT COUNT(*) INTO matching_rebuilds
                    FROM projection_rebuilds
                    WHERE projection_definition_id = NEW.id
                      AND projection_generation_id = NEW.active_generation_id
                      AND lifecycle_status IN ('RECONCILED', 'ACTIVATED');
                    SELECT COUNT(*) INTO passing_families
                    FROM projection_reconciliations reconciliation
                    JOIN projection_rebuilds rebuild
                      ON rebuild.id = reconciliation.projection_rebuild_id
                    WHERE rebuild.projection_definition_id = NEW.id
                      AND rebuild.projection_generation_id = NEW.active_generation_id
                      AND reconciliation.result_status = 'PASS';
                    IF matching_rebuilds <> 1 OR passing_families <> 5 THEN
                        RAISE EXCEPTION 'projection activation requires complete reconciliation'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_definitions_require_reconciliation
                BEFORE UPDATE OF active_generation_id ON projection_definitions
                FOR EACH ROW EXECUTE FUNCTION require_reconciled_projection_activation()
                """);
    }
}
