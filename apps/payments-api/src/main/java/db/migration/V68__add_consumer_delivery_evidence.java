package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds durable consumer deduplication and payload-free quarantine evidence. */
public final class V68__add_consumer_delivery_evidence extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE consumer_inbox (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        consumer_name VARCHAR(96) NOT NULL,
                        domain_event_id UUID NOT NULL,
                        event_record_sha256 VARCHAR(64) NOT NULL,
                        handler_version VARCHAR(32) NOT NULL,
                        projection_name VARCHAR(64) NOT NULL,
                        projection_generation_id UUID NOT NULL,
                        lifecycle_status VARCHAR(24) NOT NULL,
                        topic_name VARCHAR(96) NOT NULL,
                        broker_partition INTEGER NOT NULL,
                        broker_offset BIGINT NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        completed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_consumer_inbox PRIMARY KEY (id),
                        CONSTRAINT uk_consumer_inbox_identity UNIQUE
                            (consumer_name, domain_event_id),
                        CONSTRAINT uk_consumer_inbox_tenant_identity UNIQUE
                            (merchant_id, consumer_name, domain_event_id),
                        CONSTRAINT fk_consumer_inbox_merchant FOREIGN KEY (merchant_id)
                            REFERENCES merchants (id),
                        CONSTRAINT ck_consumer_inbox_status CHECK
                            (lifecycle_status IN ('APPLIED', 'QUARANTINED', 'HELD')),
                        CONSTRAINT ck_consumer_inbox_digest CHECK
                            (LENGTH(event_record_sha256) = 64),
                        CONSTRAINT ck_consumer_inbox_partition CHECK
                            (broker_partition BETWEEN 0 AND 5),
                        CONSTRAINT ck_consumer_inbox_offset CHECK (broker_offset >= 0),
                        CONSTRAINT ck_consumer_inbox_time CHECK (completed_at >= created_at)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE consumer_quarantine (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        consumer_name VARCHAR(96) NOT NULL,
                        domain_event_id UUID NOT NULL,
                        source_domain_event_id UUID,
                        event_record_sha256 VARCHAR(64) NOT NULL,
                        reason_code VARCHAR(48) NOT NULL,
                        lifecycle_status VARCHAR(24) NOT NULL,
                        compatibility_adapter_version VARCHAR(32),
                        resolved_by_operator_id UUID,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        resolved_at TIMESTAMP(6) WITH TIME ZONE,
                        CONSTRAINT pk_consumer_quarantine PRIMARY KEY (id),
                        CONSTRAINT uk_consumer_quarantine_identity UNIQUE
                            (merchant_id, consumer_name, domain_event_id),
                        CONSTRAINT fk_consumer_quarantine_inbox
                            FOREIGN KEY (merchant_id, consumer_name, domain_event_id)
                            REFERENCES consumer_inbox
                                (merchant_id, consumer_name, domain_event_id),
                        CONSTRAINT fk_consumer_quarantine_source_event
                            FOREIGN KEY (merchant_id, source_domain_event_id)
                            REFERENCES domain_events (merchant_id, id),
                        CONSTRAINT fk_consumer_quarantine_operator
                            FOREIGN KEY (resolved_by_operator_id)
                            REFERENCES platform_operators (id),
                        CONSTRAINT ck_consumer_quarantine_reason CHECK (reason_code IN
                            ('UNSUPPORTED_EVENT_TYPE', 'UNSUPPORTED_EVENT_VERSION',
                             'INVALID_ENVELOPE', 'INVALID_SCHEMA', 'RECORD_KEY_MISMATCH',
                             'PAYLOAD_DIGEST_MISMATCH', 'RECORD_DIGEST_MISMATCH',
                             'TENANT_STREAM_DISAGREEMENT', 'PAYLOAD_LIMIT_EXCEEDED',
                             'IMPOSSIBLE_STREAM_VERSION', 'MISSING_PREDECESSOR',
                             'PREDECESSOR_UNAVAILABLE', 'PROJECTION_INVARIANT_FAILURE')),
                        CONSTRAINT ck_consumer_quarantine_status CHECK
                            (lifecycle_status IN ('OPEN', 'RETRY_REQUESTED',
                                'RESOLVED', 'ABANDONED')),
                        CONSTRAINT ck_consumer_quarantine_resolution CHECK (
                            (lifecycle_status IN ('OPEN', 'RETRY_REQUESTED')
                                AND resolved_at IS NULL)
                            OR (lifecycle_status IN ('RESOLVED', 'ABANDONED')
                                AND resolved_at IS NOT NULL
                                AND resolved_by_operator_id IS NOT NULL)),
                        CONSTRAINT ck_consumer_quarantine_digest CHECK
                            (LENGTH(event_record_sha256) = 64),
                        CONSTRAINT ck_consumer_quarantine_time CHECK
                            (updated_at >= created_at
                                AND (resolved_at IS NULL OR resolved_at >= created_at))
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_consumer_inbox_projection
                    ON consumer_inbox
                        (merchant_id, projection_name, projection_generation_id,
                         lifecycle_status, completed_at, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_consumer_inbox_broker_position
                    ON consumer_inbox
                        (consumer_name, topic_name, broker_partition, broker_offset)
                    """);
            statement.execute("""
                    CREATE INDEX idx_consumer_quarantine_open
                    ON consumer_quarantine
                        (lifecycle_status, consumer_name, reason_code, created_at, id)
                    """);
            if (postgresql) {
                statement.execute("""
                        ALTER TABLE consumer_inbox
                        ADD CONSTRAINT ck_consumer_inbox_digest_lower_hex
                        CHECK (event_record_sha256 ~ '^[0-9a-f]{64}$')
                        """);
                statement.execute("""
                        ALTER TABLE consumer_quarantine
                        ADD CONSTRAINT ck_consumer_quarantine_digest_lower_hex
                        CHECK (event_record_sha256 ~ '^[0-9a-f]{64}$')
                        """);
                statement.execute("""
                        CREATE OR REPLACE FUNCTION protect_consumer_inbox_lifecycle()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $function$
                        BEGIN
                            IF TG_OP = 'DELETE'
                               OR NEW.id <> OLD.id
                               OR NEW.merchant_id <> OLD.merchant_id
                               OR NEW.consumer_name <> OLD.consumer_name
                               OR NEW.domain_event_id <> OLD.domain_event_id
                               OR NEW.event_record_sha256 <> OLD.event_record_sha256
                               OR NEW.handler_version <> OLD.handler_version
                               OR NEW.projection_name <> OLD.projection_name
                               OR NEW.projection_generation_id <> OLD.projection_generation_id
                               OR NEW.topic_name <> OLD.topic_name
                               OR NEW.broker_partition <> OLD.broker_partition
                               OR NEW.broker_offset <> OLD.broker_offset
                               OR NEW.created_at <> OLD.created_at
                               OR NOT (
                                   OLD.lifecycle_status = NEW.lifecycle_status
                                   OR (OLD.lifecycle_status IN ('QUARANTINED', 'HELD')
                                       AND NEW.lifecycle_status = 'APPLIED')
                               ) THEN
                                RAISE EXCEPTION 'consumer inbox mutation is invalid'
                                    USING ERRCODE = '55000';
                            END IF;
                            RETURN NEW;
                        END;
                        $function$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_consumer_inbox_lifecycle_protected
                        BEFORE UPDATE OR DELETE ON consumer_inbox
                        FOR EACH ROW EXECUTE FUNCTION protect_consumer_inbox_lifecycle()
                        """);
                statement.execute("""
                        CREATE OR REPLACE FUNCTION protect_consumer_quarantine_lifecycle()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $function$
                        BEGIN
                            IF TG_OP = 'DELETE'
                               OR NEW.id <> OLD.id
                               OR NEW.merchant_id <> OLD.merchant_id
                               OR NEW.consumer_name <> OLD.consumer_name
                               OR NEW.domain_event_id <> OLD.domain_event_id
                               OR NEW.source_domain_event_id IS DISTINCT FROM OLD.source_domain_event_id
                               OR NEW.event_record_sha256 <> OLD.event_record_sha256
                               OR NEW.reason_code <> OLD.reason_code
                               OR NEW.created_at <> OLD.created_at
                               OR NOT (
                                   OLD.lifecycle_status = NEW.lifecycle_status
                                   OR (OLD.lifecycle_status = 'OPEN'
                                       AND NEW.lifecycle_status IN
                                           ('RETRY_REQUESTED', 'ABANDONED'))
                                   OR (OLD.lifecycle_status = 'RETRY_REQUESTED'
                                       AND NEW.lifecycle_status IN ('RESOLVED', 'ABANDONED'))
                               ) THEN
                                RAISE EXCEPTION 'consumer quarantine mutation is invalid'
                                    USING ERRCODE = '55000';
                            END IF;
                            RETURN NEW;
                        END;
                        $function$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_consumer_quarantine_lifecycle_protected
                        BEFORE UPDATE OR DELETE ON consumer_quarantine
                        FOR EACH ROW EXECUTE FUNCTION protect_consumer_quarantine_lifecycle()
                        """);
            }
        }
    }
}
