package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds immutable source-snapshot and per-stream cutoff manifests for rebuilds. */
public final class V72__add_projection_cutoff_manifests extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE projection_bootstrap_manifests (
                        id UUID NOT NULL,
                        projection_definition_id UUID NOT NULL,
                        source_cutoff_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        fact_count BIGINT NOT NULL,
                        canonical_manifest_sha256 VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_bootstrap_manifests PRIMARY KEY (id),
                        CONSTRAINT uk_projection_bootstrap_manifests_identity UNIQUE
                            (projection_definition_id, source_cutoff_at,
                             canonical_manifest_sha256),
                        CONSTRAINT fk_projection_bootstrap_manifests_definition
                            FOREIGN KEY (projection_definition_id)
                            REFERENCES projection_definitions (id),
                        CONSTRAINT ck_projection_bootstrap_manifests_count CHECK (fact_count >= 0),
                        CONSTRAINT ck_projection_bootstrap_manifests_digest CHECK
                            (LENGTH(canonical_manifest_sha256) = 64),
                        CONSTRAINT ck_projection_bootstrap_manifests_time CHECK
                            (created_at >= source_cutoff_at)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_bootstrap_facts (
                        id UUID NOT NULL,
                        bootstrap_manifest_id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        source_type VARCHAR(48) NOT NULL,
                        source_id UUID NOT NULL,
                        provenance VARCHAR(24) NOT NULL,
                        canonical_fact_bytes BYTEA NOT NULL,
                        canonical_fact_sha256 VARCHAR(64) NOT NULL,
                        source_cutoff_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_bootstrap_facts PRIMARY KEY (id),
                        CONSTRAINT uk_projection_bootstrap_facts_source UNIQUE
                            (bootstrap_manifest_id, merchant_id, source_type, source_id),
                        CONSTRAINT fk_projection_bootstrap_facts_manifest
                            FOREIGN KEY (bootstrap_manifest_id)
                            REFERENCES projection_bootstrap_manifests (id),
                        CONSTRAINT fk_projection_bootstrap_facts_merchant
                            FOREIGN KEY (merchant_id) REFERENCES merchants (id),
                        CONSTRAINT ck_projection_bootstrap_facts_source CHECK (source_type IN
                            ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'FRAUD_DECISION',
                             'AUTHORIZATION', 'CAPTURE', 'REFUND', 'CHARGEBACK',
                             'MANUAL_REVIEW', 'FRAUD_LABEL')),
                        CONSTRAINT ck_projection_bootstrap_facts_provenance CHECK
                            (provenance = 'SOURCE_SNAPSHOT'),
                        CONSTRAINT ck_projection_bootstrap_facts_size CHECK
                            (OCTET_LENGTH(canonical_fact_bytes) BETWEEN 2 AND 32768),
                        CONSTRAINT ck_projection_bootstrap_facts_digest CHECK
                            (LENGTH(canonical_fact_sha256) = 64),
                        CONSTRAINT ck_projection_bootstrap_facts_time CHECK (
                            created_at >= source_cutoff_at)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_cutoff_manifests (
                        id UUID NOT NULL,
                        projection_definition_id UUID NOT NULL,
                        bootstrap_manifest_id UUID,
                        handler_version VARCHAR(32) NOT NULL,
                        traffic_origin_policy VARCHAR(24) NOT NULL,
                        stream_count BIGINT NOT NULL,
                        event_count BIGINT NOT NULL,
                        bootstrap_fact_count BIGINT NOT NULL,
                        canonical_manifest_sha256 VARCHAR(64) NOT NULL,
                        bootstrap_manifest_sha256 VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_projection_cutoff_manifests PRIMARY KEY (id),
                        CONSTRAINT uk_projection_cutoff_manifests_digest UNIQUE
                            (projection_definition_id, canonical_manifest_sha256),
                        CONSTRAINT fk_projection_cutoff_manifests_definition
                            FOREIGN KEY (projection_definition_id)
                            REFERENCES projection_definitions (id),
                        CONSTRAINT fk_projection_cutoff_manifests_bootstrap
                            FOREIGN KEY (bootstrap_manifest_id)
                            REFERENCES projection_bootstrap_manifests (id),
                        CONSTRAINT ck_projection_cutoff_manifests_origin CHECK
                            (traffic_origin_policy IN ('ALL', 'LIVE_ONLY', 'SYNTHETIC_ONLY')),
                        CONSTRAINT ck_projection_cutoff_manifests_counts CHECK (
                            stream_count >= 0 AND event_count >= 0
                            AND bootstrap_fact_count >= 0),
                        CONSTRAINT ck_projection_cutoff_manifests_digests CHECK (
                            LENGTH(canonical_manifest_sha256) = 64
                            AND LENGTH(bootstrap_manifest_sha256) = 64)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE projection_cutoff_stream_positions (
                        id UUID NOT NULL,
                        cutoff_manifest_id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        stream_type VARCHAR(32) NOT NULL,
                        stream_id UUID NOT NULL,
                        maximum_stream_version BIGINT NOT NULL,
                        terminal_domain_event_id UUID NOT NULL,
                        terminal_event_record_sha256 VARCHAR(64) NOT NULL,
                        CONSTRAINT pk_projection_cutoff_stream_positions PRIMARY KEY (id),
                        CONSTRAINT uk_projection_cutoff_stream_positions_stream UNIQUE
                            (cutoff_manifest_id, merchant_id, stream_type, stream_id),
                        CONSTRAINT fk_projection_cutoff_stream_positions_manifest
                            FOREIGN KEY (cutoff_manifest_id)
                            REFERENCES projection_cutoff_manifests (id),
                        CONSTRAINT fk_projection_cutoff_stream_positions_merchant
                            FOREIGN KEY (merchant_id) REFERENCES merchants (id),
                        CONSTRAINT fk_projection_cutoff_stream_positions_head
                            FOREIGN KEY (merchant_id, stream_type, stream_id)
                            REFERENCES event_stream_heads (merchant_id, stream_type, stream_id),
                        CONSTRAINT fk_projection_cutoff_stream_positions_event
                            FOREIGN KEY (merchant_id, terminal_domain_event_id)
                            REFERENCES domain_events (merchant_id, id),
                        CONSTRAINT ck_projection_cutoff_stream_positions_type CHECK (stream_type IN
                            ('PAYMENT', 'RISK_CONTROL', 'RISK_INCIDENT', 'DISPUTE',
                             'WEBHOOK_ENDPOINT', 'DEVELOPER_SCENARIO')),
                        CONSTRAINT ck_projection_cutoff_stream_positions_version CHECK
                            (maximum_stream_version > 0),
                        CONSTRAINT ck_projection_cutoff_stream_positions_digest CHECK
                            (LENGTH(terminal_event_record_sha256) = 64)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_bootstrap_facts_manifest
                    ON projection_bootstrap_facts
                        (bootstrap_manifest_id, merchant_id, source_type, source_id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_projection_cutoff_stream_positions_manifest
                    ON projection_cutoff_stream_positions
                        (cutoff_manifest_id, merchant_id, stream_type, stream_id,
                         maximum_stream_version)
                    """);
            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE projection_bootstrap_manifests
                ADD CONSTRAINT ck_projection_bootstrap_manifests_digest_lower_hex
                CHECK (canonical_manifest_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE projection_bootstrap_facts
                ADD CONSTRAINT ck_projection_bootstrap_facts_digest_lower_hex
                CHECK (canonical_fact_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE projection_cutoff_manifests
                ADD CONSTRAINT ck_projection_cutoff_manifests_digest_lower_hex
                CHECK (canonical_manifest_sha256 ~ '^[0-9a-f]{64}$'
                    AND bootstrap_manifest_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE projection_cutoff_stream_positions
                ADD CONSTRAINT ck_projection_cutoff_stream_positions_digest_lower_hex
                CHECK (terminal_event_record_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_bootstrap_manifests_immutable
                BEFORE UPDATE OR DELETE ON projection_bootstrap_manifests
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_bootstrap_facts_immutable
                BEFORE UPDATE OR DELETE ON projection_bootstrap_facts
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_cutoff_manifests_immutable
                BEFORE UPDATE OR DELETE ON projection_cutoff_manifests
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_projection_cutoff_stream_positions_immutable
                BEFORE UPDATE OR DELETE ON projection_cutoff_stream_positions
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
    }
}
