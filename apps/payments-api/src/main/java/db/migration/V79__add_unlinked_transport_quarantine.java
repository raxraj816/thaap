package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds tenant-free, payload-free evidence for broker records without authenticated source lineage. */
public final class V79__add_unlinked_transport_quarantine extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(
                connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE consumer_transport_quarantine (
                        id UUID NOT NULL,
                        consumer_name VARCHAR(96) NOT NULL,
                        topic_name VARCHAR(96) NOT NULL,
                        broker_partition INTEGER NOT NULL,
                        broker_offset BIGINT NOT NULL,
                        received_record_sha256 VARCHAR(64) NOT NULL,
                        received_record_size_bytes BIGINT NOT NULL,
                        reason_code VARCHAR(48) NOT NULL,
                        lifecycle_status VARCHAR(24) NOT NULL,
                        recoverable BOOLEAN NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_consumer_transport_quarantine PRIMARY KEY (id),
                        CONSTRAINT uk_consumer_transport_quarantine_position UNIQUE
                            (consumer_name, topic_name, broker_partition, broker_offset),
                        CONSTRAINT uk_consumer_transport_quarantine_identity UNIQUE
                            (consumer_name, topic_name, broker_partition, broker_offset,
                             received_record_sha256),
                        CONSTRAINT ck_consumer_transport_quarantine_partition CHECK
                            (broker_partition BETWEEN 0 AND 5),
                        CONSTRAINT ck_consumer_transport_quarantine_offset CHECK
                            (broker_offset >= 0),
                        CONSTRAINT ck_consumer_transport_quarantine_size CHECK
                            (received_record_size_bytes >= 0),
                        CONSTRAINT ck_consumer_transport_quarantine_digest CHECK
                            (LENGTH(received_record_sha256) = 64),
                        CONSTRAINT ck_consumer_transport_quarantine_reason CHECK
                            (reason_code IN ('UNSUPPORTED_EVENT_TYPE',
                                'UNSUPPORTED_EVENT_VERSION', 'INVALID_ENVELOPE',
                                'INVALID_SCHEMA', 'RECORD_KEY_MISMATCH',
                                'PAYLOAD_DIGEST_MISMATCH', 'RECORD_DIGEST_MISMATCH',
                                'TENANT_STREAM_DISAGREEMENT', 'PAYLOAD_LIMIT_EXCEEDED',
                                'SOURCE_IDENTITY_UNAVAILABLE',
                                'SOURCE_IDENTITY_CONFLICT')),
                        CONSTRAINT ck_consumer_transport_quarantine_status CHECK
                            (lifecycle_status = 'OPEN'),
                        CONSTRAINT ck_consumer_transport_quarantine_recovery CHECK
                            (recoverable = FALSE)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_consumer_transport_quarantine_inventory
                    ON consumer_transport_quarantine
                        (lifecycle_status, consumer_name, reason_code, created_at, id)
                    """);
            if (postgresql) {
                statement.execute("""
                        ALTER TABLE consumer_transport_quarantine
                        ADD CONSTRAINT ck_consumer_transport_quarantine_digest_lower_hex
                        CHECK (received_record_sha256 ~ '^[0-9a-f]{64}$')
                        """);
                statement.execute("""
                        CREATE OR REPLACE FUNCTION protect_consumer_transport_quarantine()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $function$
                        BEGIN
                            RAISE EXCEPTION 'consumer transport quarantine is immutable'
                                USING ERRCODE = '55000';
                        END;
                        $function$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_consumer_transport_quarantine_immutable
                        BEFORE UPDATE OR DELETE ON consumer_transport_quarantine
                        FOR EACH ROW EXECUTE FUNCTION protect_consumer_transport_quarantine()
                        """);
            }
        }
    }
}
