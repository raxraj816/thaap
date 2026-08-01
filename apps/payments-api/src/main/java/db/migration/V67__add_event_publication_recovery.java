package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds the fenced publication lifecycle and append-only transport evidence. */
public final class V67__add_event_publication_recovery extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE event_publications DROP CONSTRAINT ck_event_publications_status");
            statement.execute("ALTER TABLE event_publications ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0");
            statement.execute("ALTER TABLE event_publications ADD COLUMN total_attempt_count INTEGER NOT NULL DEFAULT 0");
            statement.execute("ALTER TABLE event_publications ADD COLUMN next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP");
            statement.execute("ALTER TABLE event_publications ADD COLUMN lease_owner VARCHAR(128)");
            statement.execute("ALTER TABLE event_publications ADD COLUMN lease_token UUID");
            statement.execute("ALTER TABLE event_publications ADD COLUMN lease_expires_at TIMESTAMP(6) WITH TIME ZONE");
            statement.execute("ALTER TABLE event_publications ADD COLUMN last_outcome VARCHAR(32)");
            statement.execute("ALTER TABLE event_publications ADD COLUMN last_error_code VARCHAR(48)");
            statement.execute("ALTER TABLE event_publications ADD COLUMN published_partition INTEGER");
            statement.execute("ALTER TABLE event_publications ADD COLUMN published_offset BIGINT");
            statement.execute("ALTER TABLE event_publications ADD COLUMN acknowledged_at TIMESTAMP(6) WITH TIME ZONE");
            statement.execute("ALTER TABLE event_publications ADD COLUMN blocked_at TIMESTAMP(6) WITH TIME ZONE");
            statement.execute("ALTER TABLE event_publications ADD COLUMN updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP");
            statement.execute("""
                    ALTER TABLE event_publications ADD CONSTRAINT ck_event_publications_status
                    CHECK (lifecycle_status IN
                        ('PENDING', 'CLAIMED', 'RETRY_SCHEDULED', 'BLOCKED', 'PUBLISHED'))
                    """);
            statement.execute("""
                    ALTER TABLE event_publications ADD CONSTRAINT ck_event_publications_counts
                    CHECK (retry_count BETWEEN 0 AND 10
                        AND total_attempt_count >= retry_count)
                    """);
            statement.execute("""
                    ALTER TABLE event_publications ADD CONSTRAINT ck_event_publications_lease
                    CHECK ((lifecycle_status = 'CLAIMED'
                                AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                                AND lease_expires_at IS NOT NULL)
                        OR (lifecycle_status <> 'CLAIMED'
                                AND lease_owner IS NULL AND lease_token IS NULL
                                AND lease_expires_at IS NULL))
                    """);
            statement.execute("""
                    ALTER TABLE event_publications ADD CONSTRAINT ck_event_publications_ack
                    CHECK ((lifecycle_status = 'PUBLISHED'
                                AND published_partition IS NOT NULL
                                AND published_partition BETWEEN 0 AND 5
                                AND published_offset IS NOT NULL AND published_offset >= 0
                                AND acknowledged_at IS NOT NULL)
                        OR (lifecycle_status <> 'PUBLISHED'
                                AND published_partition IS NULL AND published_offset IS NULL
                                AND acknowledged_at IS NULL))
                    """);
            statement.execute("""
                    ALTER TABLE event_publications ADD CONSTRAINT ck_event_publications_blocked
                    CHECK ((lifecycle_status = 'BLOCKED' AND blocked_at IS NOT NULL)
                        OR (lifecycle_status <> 'BLOCKED' AND blocked_at IS NULL))
                    """);
            statement.execute("""
                    ALTER TABLE event_publications ADD CONSTRAINT ck_event_publications_time
                    CHECK (next_attempt_at >= created_at AND updated_at >= created_at
                        AND (lease_expires_at IS NULL OR lease_expires_at > updated_at)
                        AND (acknowledged_at IS NULL OR acknowledged_at >= created_at)
                        AND (blocked_at IS NULL OR blocked_at >= created_at))
                    """);

            statement.execute("""
                    CREATE TABLE event_publication_attempts (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        publication_id UUID NOT NULL,
                        domain_event_id UUID NOT NULL,
                        attempt_number INTEGER NOT NULL,
                        fencing_token UUID NOT NULL,
                        outcome VARCHAR(32) NOT NULL,
                        safe_error_code VARCHAR(48),
                        topic_name VARCHAR(96) NOT NULL,
                        broker_partition INTEGER,
                        broker_offset BIGINT,
                        worker_id VARCHAR(128) NOT NULL,
                        started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        completed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_event_publication_attempts PRIMARY KEY (id),
                        CONSTRAINT uk_event_publication_attempts_number UNIQUE
                            (merchant_id, publication_id, attempt_number),
                        CONSTRAINT fk_event_publication_attempts_publication
                            FOREIGN KEY (merchant_id, publication_id)
                            REFERENCES event_publications (merchant_id, id),
                        CONSTRAINT fk_event_publication_attempts_event
                            FOREIGN KEY (merchant_id, domain_event_id)
                            REFERENCES domain_events (merchant_id, id),
                        CONSTRAINT ck_event_publication_attempts_number CHECK (attempt_number > 0),
                        CONSTRAINT ck_event_publication_attempts_outcome CHECK (outcome IN
                            ('ACKNOWLEDGED', 'RETRYABLE_FAILURE', 'UNKNOWN',
                             'PERMANENT_FAILURE', 'LEASE_RECOVERED')),
                        CONSTRAINT ck_event_publication_attempts_error CHECK (
                            (outcome = 'ACKNOWLEDGED' AND safe_error_code IS NULL
                                AND broker_partition BETWEEN 0 AND 5
                                AND broker_offset >= 0)
                            OR (outcome <> 'ACKNOWLEDGED' AND safe_error_code IS NOT NULL
                                AND broker_partition IS NULL AND broker_offset IS NULL)),
                        CONSTRAINT ck_event_publication_attempts_time CHECK
                            (completed_at >= started_at)
                    )
                    """);

            statement.execute("""
                    CREATE TABLE platform_data_operation_requests (
                        id UUID NOT NULL,
                        platform_operator_id UUID NOT NULL,
                        platform_credential_id UUID NOT NULL,
                        operation VARCHAR(40) NOT NULL,
                        request_identity_hmac VARCHAR(64) NOT NULL,
                        request_identity_key_version VARCHAR(32) NOT NULL,
                        request_digest VARCHAR(64) NOT NULL,
                        request_digest_key_version VARCHAR(32) NOT NULL,
                        target_id UUID,
                        result_id UUID,
                        result_status VARCHAR(32) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        completed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_platform_data_operation_requests PRIMARY KEY (id),
                        CONSTRAINT uk_platform_data_operation_requests_identity
                            UNIQUE (request_identity_hmac),
                        CONSTRAINT fk_platform_data_operation_requests_operator
                            FOREIGN KEY (platform_operator_id) REFERENCES platform_operators (id),
                        CONSTRAINT fk_platform_data_operation_requests_credential
                            FOREIGN KEY (platform_credential_id)
                            REFERENCES platform_operator_credentials (id),
                        CONSTRAINT ck_platform_data_operation_requests_operation CHECK (operation IN
                            ('RETRY_PUBLICATION', 'RETRY_QUARANTINE', 'START_REBUILD',
                             'CANCEL_REBUILD', 'RECONCILE_REBUILD', 'ACTIVATE_REBUILD',
                             'ROLLBACK_PROJECTION')),
                        CONSTRAINT ck_platform_data_operation_requests_digest CHECK (
                            LENGTH(request_identity_hmac) = 64 AND LENGTH(request_digest) = 64),
                        CONSTRAINT ck_platform_data_operation_requests_key_versions CHECK (
                            LENGTH(request_identity_key_version) BETWEEN 1 AND 32
                            AND LENGTH(request_digest_key_version) BETWEEN 1 AND 32),
                        CONSTRAINT ck_platform_data_operation_requests_status CHECK
                            (result_status IN ('COMPLETED', 'NO_CHANGE')),
                        CONSTRAINT ck_platform_data_operation_requests_time CHECK
                            (completed_at >= created_at)
                    )
                    """);

            statement.execute("DROP INDEX idx_event_publications_pending");
            statement.execute("""
                    CREATE INDEX idx_event_publications_due
                    ON event_publications (lifecycle_status, next_attempt_at, created_at, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_event_publications_lease
                    ON event_publications (lifecycle_status, lease_expires_at, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_event_publication_attempts_publication
                    ON event_publication_attempts
                        (merchant_id, publication_id, attempt_number)
                    """);
            statement.execute("""
                    CREATE INDEX idx_platform_data_operation_requests_actor
                    ON platform_data_operation_requests
                        (platform_operator_id, operation, created_at, id)
                    """);

            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE event_publication_attempts
                ADD CONSTRAINT ck_event_publication_attempts_safe_error
                CHECK (safe_error_code IS NULL OR safe_error_code ~ '^[A-Z][A-Z0-9_]{0,47}$')
                """);
        statement.execute("""
                ALTER TABLE platform_data_operation_requests
                ADD CONSTRAINT ck_platform_data_operation_requests_lower_hex
                CHECK (request_identity_hmac ~ '^[0-9a-f]{64}$'
                    AND request_digest ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_event_publication_lifecycle()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF NEW.retry_count < OLD.retry_count
                       AND NOT (OLD.lifecycle_status = 'BLOCKED'
                           AND NEW.lifecycle_status = 'PENDING'
                           AND NEW.retry_count = 0) THEN
                        RAISE EXCEPTION 'publication retry count cannot regress'
                            USING ERRCODE = '55000';
                    END IF;
                    IF NEW.total_attempt_count < OLD.total_attempt_count THEN
                        RAISE EXCEPTION 'publication attempt count cannot regress'
                            USING ERRCODE = '55000';
                    END IF;
                    IF NOT (
                        OLD.lifecycle_status = NEW.lifecycle_status
                        OR (OLD.lifecycle_status IN ('PENDING', 'RETRY_SCHEDULED')
                            AND NEW.lifecycle_status = 'CLAIMED')
                        OR (OLD.lifecycle_status = 'CLAIMED'
                            AND NEW.lifecycle_status IN
                                ('CLAIMED', 'PUBLISHED', 'RETRY_SCHEDULED', 'BLOCKED'))
                        OR (OLD.lifecycle_status = 'BLOCKED'
                            AND NEW.lifecycle_status = 'PENDING')) THEN
                        RAISE EXCEPTION 'invalid publication lifecycle transition'
                            USING ERRCODE = '55000';
                    END IF;
                    IF OLD.lifecycle_status = 'PUBLISHED' AND NEW.lifecycle_status <> 'PUBLISHED' THEN
                        RAISE EXCEPTION 'published event cannot be reopened'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_event_publications_lifecycle_protected
                BEFORE UPDATE ON event_publications
                FOR EACH ROW EXECUTE FUNCTION protect_event_publication_lifecycle()
                """);
        statement.execute("""
                CREATE TRIGGER trg_event_publication_attempts_immutable
                BEFORE UPDATE OR DELETE ON event_publication_attempts
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_platform_data_operation_requests_immutable
                BEFORE UPDATE OR DELETE ON platform_data_operation_requests
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
    }
}
