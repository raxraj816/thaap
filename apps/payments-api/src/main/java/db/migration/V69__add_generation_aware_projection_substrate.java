package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds versioned projection definitions, generations, checkpoints, and stream positions. */
public final class V69__add_generation_aware_projection_substrate extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE projection_definitions (
                        id UUID NOT NULL,
                        projection_name VARCHAR(64) NOT NULL,
                        handler_version VARCHAR(32) NOT NULL,
                        lifecycle_status VARCHAR(16) NOT NULL,
                        active_generation_id UUID,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_definitions PRIMARY KEY (id),
                        CONSTRAINT uk_projection_definitions_name UNIQUE (projection_name),
                        CONSTRAINT ck_projection_definitions_name CHECK
                            (projection_name IN ('payment-risk-v1', 'merchant-risk-v1')),
                        CONSTRAINT ck_projection_definitions_handler CHECK
                            (LENGTH(handler_version) BETWEEN 1 AND 32),
                        CONSTRAINT ck_projection_definitions_status CHECK
                            (lifecycle_status IN ('ENABLED', 'DISABLED')),
                        CONSTRAINT ck_projection_definitions_time CHECK (updated_at >= created_at)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_generations (
                        id UUID NOT NULL,
                        projection_definition_id UUID NOT NULL,
                        generation_number BIGINT NOT NULL,
                        handler_version VARCHAR(32) NOT NULL,
                        build_source VARCHAR(24) NOT NULL,
                        lifecycle_status VARCHAR(24) NOT NULL,
                        source_cutoff_digest VARCHAR(64),
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        reconciled_at TIMESTAMP(6) WITH TIME ZONE,
                        activated_at TIMESTAMP(6) WITH TIME ZONE,
                        retired_at TIMESTAMP(6) WITH TIME ZONE,
                        CONSTRAINT pk_projection_generations PRIMARY KEY (id),
                        CONSTRAINT uk_projection_generations_number UNIQUE
                            (projection_definition_id, generation_number),
                        CONSTRAINT uk_projection_generations_definition_id UNIQUE
                            (projection_definition_id, id),
                        CONSTRAINT fk_projection_generations_definition
                            FOREIGN KEY (projection_definition_id)
                            REFERENCES projection_definitions (id),
                        CONSTRAINT ck_projection_generations_number CHECK (generation_number > 0),
                        CONSTRAINT ck_projection_generations_source CHECK
                            (build_source IN ('LIVE', 'SOURCE_SNAPSHOT', 'REBUILD')),
                        CONSTRAINT ck_projection_generations_status CHECK (lifecycle_status IN
                            ('BUILDING', 'CATCHING_UP', 'RECONCILED', 'ACTIVE',
                             'RETIRED', 'FAILED', 'CANCELED')),
                        CONSTRAINT ck_projection_generations_digest CHECK
                            (source_cutoff_digest IS NULL OR LENGTH(source_cutoff_digest) = 64),
                        CONSTRAINT ck_projection_generations_times CHECK (
                            (reconciled_at IS NULL OR reconciled_at >= created_at)
                            AND (activated_at IS NULL OR activated_at >= created_at)
                            AND (retired_at IS NULL OR retired_at >= created_at))
                    )
                    """);
            statement.execute("""
                    ALTER TABLE projection_definitions
                    ADD CONSTRAINT fk_projection_definitions_active_generation
                    FOREIGN KEY (id, active_generation_id)
                    REFERENCES projection_generations (projection_definition_id, id)
                    """);
            statement.execute("""
                    CREATE TABLE projection_checkpoints (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        projection_generation_id UUID NOT NULL,
                        consumer_name VARCHAR(96) NOT NULL,
                        topic_name VARCHAR(96) NOT NULL,
                        broker_partition INTEGER NOT NULL,
                        last_broker_offset BIGINT NOT NULL,
                        last_domain_event_id UUID NOT NULL,
                        last_event_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        last_event_recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        last_processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        lifecycle_status VARCHAR(16) NOT NULL,
                        CONSTRAINT pk_projection_checkpoints PRIMARY KEY (id),
                        CONSTRAINT uk_projection_checkpoints_position UNIQUE
                            (merchant_id, projection_generation_id, consumer_name,
                             topic_name, broker_partition),
                        CONSTRAINT fk_projection_checkpoints_merchant FOREIGN KEY (merchant_id)
                            REFERENCES merchants (id),
                        CONSTRAINT fk_projection_checkpoints_generation
                            FOREIGN KEY (projection_generation_id)
                            REFERENCES projection_generations (id),
                        CONSTRAINT fk_projection_checkpoints_event
                            FOREIGN KEY (merchant_id, last_domain_event_id)
                            REFERENCES domain_events (merchant_id, id),
                        CONSTRAINT ck_projection_checkpoints_partition CHECK
                            (broker_partition BETWEEN 0 AND 5),
                        CONSTRAINT ck_projection_checkpoints_offset CHECK
                            (last_broker_offset >= 0),
                        CONSTRAINT ck_projection_checkpoints_status CHECK
                            (lifecycle_status IN ('CURRENT', 'HELD', 'STALE')),
                        CONSTRAINT ck_projection_checkpoints_time CHECK (
                            last_event_recorded_at >= last_event_occurred_at
                            AND last_processed_at >= last_event_recorded_at)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_stream_positions (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        projection_generation_id UUID NOT NULL,
                        stream_type VARCHAR(32) NOT NULL,
                        stream_id UUID NOT NULL,
                        last_stream_version BIGINT NOT NULL,
                        last_domain_event_id UUID NOT NULL,
                        last_event_record_sha256 VARCHAR(64) NOT NULL,
                        last_event_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        last_event_recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        last_processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_stream_positions PRIMARY KEY (id),
                        CONSTRAINT uk_projection_stream_positions_stream UNIQUE
                            (merchant_id, projection_generation_id, stream_type, stream_id),
                        CONSTRAINT fk_projection_stream_positions_merchant FOREIGN KEY (merchant_id)
                            REFERENCES merchants (id),
                        CONSTRAINT fk_projection_stream_positions_generation
                            FOREIGN KEY (projection_generation_id)
                            REFERENCES projection_generations (id),
                        CONSTRAINT fk_projection_stream_positions_event
                            FOREIGN KEY (merchant_id, last_domain_event_id)
                            REFERENCES domain_events (merchant_id, id),
                        CONSTRAINT fk_projection_stream_positions_head
                            FOREIGN KEY (merchant_id, stream_type, stream_id)
                            REFERENCES event_stream_heads (merchant_id, stream_type, stream_id),
                        CONSTRAINT ck_projection_stream_positions_type CHECK (stream_type IN
                            ('PAYMENT', 'RISK_CONTROL', 'RISK_INCIDENT', 'DISPUTE',
                             'WEBHOOK_ENDPOINT', 'DEVELOPER_SCENARIO')),
                        CONSTRAINT ck_projection_stream_positions_version CHECK
                            (last_stream_version > 0),
                        CONSTRAINT ck_projection_stream_positions_digest CHECK
                            (LENGTH(last_event_record_sha256) = 64),
                        CONSTRAINT ck_projection_stream_positions_time CHECK (
                            last_event_recorded_at >= last_event_occurred_at
                            AND last_processed_at >= last_event_recorded_at)
                    )
                    """);
            statement.execute("""
                    ALTER TABLE consumer_inbox ADD CONSTRAINT fk_consumer_inbox_generation
                    FOREIGN KEY (projection_generation_id) REFERENCES projection_generations (id)
                    """);

            statement.execute("""
                    INSERT INTO projection_definitions (
                        id, projection_name, handler_version, lifecycle_status,
                        active_generation_id, created_at, updated_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000901', 'payment-risk-v1',
                         'v1', 'ENABLED', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('00000000-0000-0000-0000-000000000902', 'merchant-risk-v1',
                         'v1', 'ENABLED', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO projection_generations (
                        id, projection_definition_id, generation_number, handler_version,
                        build_source, lifecycle_status, source_cutoff_digest, created_at,
                        reconciled_at, activated_at, retired_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000911',
                         '00000000-0000-0000-0000-000000000901', 1, 'v1', 'LIVE',
                         'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                         CURRENT_TIMESTAMP, NULL),
                        ('00000000-0000-0000-0000-000000000912',
                         '00000000-0000-0000-0000-000000000902', 1, 'v1', 'LIVE',
                         'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                         CURRENT_TIMESTAMP, NULL)
                    """);
            statement.execute("""
                    UPDATE projection_definitions
                    SET active_generation_id = CASE projection_name
                        WHEN 'payment-risk-v1'
                            THEN CAST('00000000-0000-0000-0000-000000000911' AS UUID)
                        ELSE CAST('00000000-0000-0000-0000-000000000912' AS UUID)
                    END,
                    updated_at = CURRENT_TIMESTAMP
                    """);

            statement.execute("""
                    CREATE INDEX idx_projection_generations_lifecycle
                    ON projection_generations
                        (projection_definition_id, lifecycle_status, generation_number, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_checkpoints_freshness
                    ON projection_checkpoints
                        (merchant_id, projection_generation_id, lifecycle_status,
                         last_processed_at, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_stream_positions_generation
                    ON projection_stream_positions
                        (projection_generation_id, merchant_id, stream_type, stream_id)
                    """);

            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE projection_generations
                ADD CONSTRAINT ck_projection_generations_digest_lower_hex
                CHECK (source_cutoff_digest IS NULL
                    OR source_cutoff_digest ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE projection_stream_positions
                ADD CONSTRAINT ck_projection_stream_positions_digest_lower_hex
                CHECK (last_event_record_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                CREATE UNIQUE INDEX uk_projection_generations_one_active
                ON projection_generations (projection_definition_id)
                WHERE lifecycle_status = 'ACTIVE'
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_projection_definition()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                DECLARE target_status VARCHAR(24);
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR NEW.id <> OLD.id
                       OR NEW.projection_name <> OLD.projection_name
                       OR NEW.created_at <> OLD.created_at THEN
                        RAISE EXCEPTION 'projection definition identity is immutable'
                            USING ERRCODE = '55000';
                    END IF;
                    IF NEW.active_generation_id IS DISTINCT FROM OLD.active_generation_id THEN
                        SELECT lifecycle_status INTO target_status
                        FROM projection_generations
                        WHERE projection_definition_id = NEW.id
                          AND id = NEW.active_generation_id;
                        IF target_status NOT IN ('RECONCILED', 'ACTIVE', 'RETIRED') THEN
                            RAISE EXCEPTION 'projection activation target is not reconciled'
                                USING ERRCODE = '55000';
                        END IF;
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_definitions_protected
                BEFORE UPDATE OR DELETE ON projection_definitions
                FOR EACH ROW EXECUTE FUNCTION protect_projection_definition()
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_projection_generation()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR NEW.id <> OLD.id
                       OR NEW.projection_definition_id <> OLD.projection_definition_id
                       OR NEW.generation_number <> OLD.generation_number
                       OR NEW.handler_version <> OLD.handler_version
                       OR NEW.build_source <> OLD.build_source
                       OR NEW.source_cutoff_digest IS DISTINCT FROM OLD.source_cutoff_digest
                       OR NEW.created_at <> OLD.created_at
                       OR NOT (
                           OLD.lifecycle_status = NEW.lifecycle_status
                           OR (OLD.lifecycle_status = 'BUILDING'
                               AND NEW.lifecycle_status IN
                                   ('CATCHING_UP', 'FAILED', 'CANCELED'))
                           OR (OLD.lifecycle_status = 'CATCHING_UP'
                               AND NEW.lifecycle_status IN
                                   ('RECONCILED', 'FAILED', 'CANCELED'))
                           OR (OLD.lifecycle_status = 'RECONCILED'
                               AND NEW.lifecycle_status IN ('ACTIVE', 'RETIRED'))
                           OR (OLD.lifecycle_status = 'ACTIVE'
                               AND NEW.lifecycle_status = 'RETIRED')
                           OR (OLD.lifecycle_status = 'RETIRED'
                               AND NEW.lifecycle_status = 'ACTIVE')
                       ) THEN
                        RAISE EXCEPTION 'projection generation mutation is invalid'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_generations_protected
                BEFORE UPDATE OR DELETE ON projection_generations
                FOR EACH ROW EXECUTE FUNCTION protect_projection_generation()
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_projection_checkpoint()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR NEW.id <> OLD.id
                       OR NEW.merchant_id <> OLD.merchant_id
                       OR NEW.projection_generation_id <> OLD.projection_generation_id
                       OR NEW.consumer_name <> OLD.consumer_name
                       OR NEW.topic_name <> OLD.topic_name
                       OR NEW.broker_partition <> OLD.broker_partition
                       OR NEW.last_broker_offset < OLD.last_broker_offset
                       OR NEW.last_processed_at < OLD.last_processed_at THEN
                        RAISE EXCEPTION 'projection checkpoint cannot regress'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_checkpoints_no_regression
                BEFORE UPDATE OR DELETE ON projection_checkpoints
                FOR EACH ROW EXECUTE FUNCTION protect_projection_checkpoint()
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_projection_stream_position()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR NEW.id <> OLD.id
                       OR NEW.merchant_id <> OLD.merchant_id
                       OR NEW.projection_generation_id <> OLD.projection_generation_id
                       OR NEW.stream_type <> OLD.stream_type
                       OR NEW.stream_id <> OLD.stream_id
                       OR NEW.last_stream_version <= OLD.last_stream_version
                       OR NEW.last_processed_at < OLD.last_processed_at THEN
                        RAISE EXCEPTION 'projection stream position cannot regress or duplicate'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_stream_positions_no_regression
                BEFORE UPDATE OR DELETE ON projection_stream_positions
                FOR EACH ROW EXECUTE FUNCTION protect_projection_stream_position()
                """);
    }
}
