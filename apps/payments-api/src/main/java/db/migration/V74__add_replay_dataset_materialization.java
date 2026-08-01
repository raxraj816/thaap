package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds immutable replay dataset manifests and aggregate-only result materialization. */
public final class V74__add_replay_dataset_materialization extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE replay_dataset_manifests (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        cutoff_manifest_id UUID NOT NULL,
                        projection_generation_id UUID NOT NULL,
                        label_availability_cutoff TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        projection_version VARCHAR(32) NOT NULL,
                        evaluator_version VARCHAR(32) NOT NULL,
                        feature_catalogue_version VARCHAR(64) NOT NULL,
                        fraud_taxonomy_version VARCHAR(64) NOT NULL,
                        traffic_origin_policy VARCHAR(24) NOT NULL,
                        bootstrap_manifest_sha256 VARCHAR(64) NOT NULL,
                        query_sha256 VARCHAR(64) NOT NULL,
                        lifecycle_status VARCHAR(16) NOT NULL,
                        lease_owner VARCHAR(128),
                        lease_token UUID,
                        lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
                        fencing_epoch BIGINT NOT NULL,
                        checkpoint_ordinal BIGINT NOT NULL,
                        eligible_count BIGINT NOT NULL,
                        included_count BIGINT NOT NULL,
                        unlabeled_count BIGINT NOT NULL,
                        contradictory_label_count BIGINT NOT NULL,
                        superseded_label_count BIGINT NOT NULL,
                        synthetic_excluded_count BIGINT NOT NULL,
                        unsupported_event_count BIGINT NOT NULL,
                        missing_feature_count BIGINT NOT NULL,
                        output_sha256 VARCHAR(64),
                        compatibility_status VARCHAR(16) NOT NULL,
                        failure_code VARCHAR(48),
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        completed_at TIMESTAMP(6) WITH TIME ZONE,
                        CONSTRAINT pk_replay_dataset_manifests PRIMARY KEY (id),
                        CONSTRAINT uk_replay_dataset_manifests_query UNIQUE
                            (merchant_id, query_sha256),
                        CONSTRAINT fk_replay_dataset_manifests_merchant FOREIGN KEY (merchant_id)
                            REFERENCES merchants (id),
                        CONSTRAINT fk_replay_dataset_manifests_cutoff
                            FOREIGN KEY (cutoff_manifest_id)
                            REFERENCES projection_cutoff_manifests (id),
                        CONSTRAINT fk_replay_dataset_manifests_generation
                            FOREIGN KEY (projection_generation_id)
                            REFERENCES projection_generations (id),
                        CONSTRAINT ck_replay_dataset_manifests_origin CHECK
                            (traffic_origin_policy IN ('ALL', 'LIVE_ONLY', 'SYNTHETIC_ONLY')),
                        CONSTRAINT ck_replay_dataset_manifests_status CHECK
                            (lifecycle_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
                        CONSTRAINT ck_replay_dataset_manifests_lease CHECK (
                            (lifecycle_status = 'RUNNING'
                                AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                                AND lease_expires_at IS NOT NULL)
                            OR (lifecycle_status <> 'RUNNING'
                                AND lease_owner IS NULL AND lease_token IS NULL
                                AND lease_expires_at IS NULL)),
                        CONSTRAINT ck_replay_dataset_manifests_digests CHECK (
                            LENGTH(bootstrap_manifest_sha256) = 64
                            AND LENGTH(query_sha256) = 64
                            AND (output_sha256 IS NULL OR LENGTH(output_sha256) = 64)),
                        CONSTRAINT ck_replay_dataset_manifests_counts CHECK (
                            fencing_epoch >= 0 AND checkpoint_ordinal >= 0
                            AND eligible_count >= 0 AND included_count >= 0
                            AND unlabeled_count >= 0 AND contradictory_label_count >= 0
                            AND superseded_label_count >= 0
                            AND synthetic_excluded_count >= 0
                            AND unsupported_event_count >= 0 AND missing_feature_count >= 0
                            AND included_count <= eligible_count),
                        CONSTRAINT ck_replay_dataset_manifests_compatibility CHECK
                            (compatibility_status IN ('PENDING', 'PASSED', 'FAILED')),
                        CONSTRAINT ck_replay_dataset_manifests_terminal CHECK (
                            (lifecycle_status = 'SUCCEEDED' AND output_sha256 IS NOT NULL
                                AND failure_code IS NULL AND completed_at IS NOT NULL)
                            OR (lifecycle_status = 'FAILED' AND output_sha256 IS NULL
                                AND failure_code IS NOT NULL AND completed_at IS NOT NULL)
                            OR (lifecycle_status IN ('QUEUED', 'RUNNING')
                                AND output_sha256 IS NULL AND failure_code IS NULL
                                AND completed_at IS NULL)),
                        CONSTRAINT ck_replay_dataset_manifests_time CHECK (
                            updated_at >= created_at
                            AND label_availability_cutoff <= created_at
                            AND (lease_expires_at IS NULL OR lease_expires_at > updated_at)
                            AND (completed_at IS NULL OR completed_at >= created_at))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE replay_dataset_results (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        replay_dataset_id UUID NOT NULL,
                        metric_code VARCHAR(48) NOT NULL,
                        bucket_code VARCHAR(64) NOT NULL,
                        population_count BIGINT NOT NULL,
                        amount_minor BIGINT,
                        result_sha256 VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_replay_dataset_results PRIMARY KEY (id),
                        CONSTRAINT uk_replay_dataset_results_bucket UNIQUE
                            (merchant_id, replay_dataset_id, metric_code, bucket_code),
                        CONSTRAINT fk_replay_dataset_results_manifest
                            FOREIGN KEY (replay_dataset_id)
                            REFERENCES replay_dataset_manifests (id),
                        CONSTRAINT fk_replay_dataset_results_merchant
                            FOREIGN KEY (merchant_id) REFERENCES merchants (id),
                        CONSTRAINT ck_replay_dataset_results_metric CHECK (metric_code IN
                            ('LABEL_OUTCOME', 'ORIGINAL_ACTION', 'TRAFFIC_ORIGIN',
                             'FEATURE_COVERAGE', 'SIMULATION_EQUIVALENCE')),
                        CONSTRAINT ck_replay_dataset_results_values CHECK (
                            population_count >= 0 AND (amount_minor IS NULL OR amount_minor >= 0)),
                        CONSTRAINT ck_replay_dataset_results_digest CHECK
                            (LENGTH(result_sha256) = 64)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_manifests_worker
                    ON replay_dataset_manifests
                        (lifecycle_status, lease_expires_at, created_at, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_manifests_tenant
                    ON replay_dataset_manifests
                        (merchant_id, created_at DESC, id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_results_manifest
                    ON replay_dataset_results
                        (merchant_id, replay_dataset_id, metric_code, bucket_code)
                    """);
            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE replay_dataset_manifests
                ADD CONSTRAINT ck_replay_dataset_manifests_digest_lower_hex
                CHECK (bootstrap_manifest_sha256 ~ '^[0-9a-f]{64}$'
                    AND query_sha256 ~ '^[0-9a-f]{64}$'
                    AND (output_sha256 IS NULL OR output_sha256 ~ '^[0-9a-f]{64}$'))
                """);
        statement.execute("""
                ALTER TABLE replay_dataset_results
                ADD CONSTRAINT ck_replay_dataset_results_digest_lower_hex
                CHECK (result_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_replay_dataset_manifest()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR NEW.id <> OLD.id
                       OR NEW.merchant_id <> OLD.merchant_id
                       OR NEW.cutoff_manifest_id <> OLD.cutoff_manifest_id
                       OR NEW.projection_generation_id <> OLD.projection_generation_id
                       OR NEW.label_availability_cutoff <> OLD.label_availability_cutoff
                       OR NEW.projection_version <> OLD.projection_version
                       OR NEW.evaluator_version <> OLD.evaluator_version
                       OR NEW.feature_catalogue_version <> OLD.feature_catalogue_version
                       OR NEW.fraud_taxonomy_version <> OLD.fraud_taxonomy_version
                       OR NEW.traffic_origin_policy <> OLD.traffic_origin_policy
                       OR NEW.bootstrap_manifest_sha256 <> OLD.bootstrap_manifest_sha256
                       OR NEW.query_sha256 <> OLD.query_sha256
                       OR NEW.created_at <> OLD.created_at
                       OR NEW.fencing_epoch < OLD.fencing_epoch
                       OR NEW.checkpoint_ordinal < OLD.checkpoint_ordinal
                       OR NEW.eligible_count < OLD.eligible_count
                       OR NEW.included_count < OLD.included_count
                       OR NOT (
                           OLD.lifecycle_status = NEW.lifecycle_status
                           OR (OLD.lifecycle_status = 'QUEUED'
                               AND NEW.lifecycle_status IN ('RUNNING', 'FAILED'))
                           OR (OLD.lifecycle_status = 'RUNNING'
                               AND NEW.lifecycle_status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
                       ) THEN
                        RAISE EXCEPTION 'replay dataset mutation is invalid'
                            USING ERRCODE = '55000';
                    END IF;
                    IF NEW.fencing_epoch > OLD.fencing_epoch
                       AND NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token THEN
                        RAISE EXCEPTION 'new dataset fence requires a new lease token'
                            USING ERRCODE = '55000';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """);
        statement.execute("""
                CREATE TRIGGER trg_replay_dataset_manifests_protected
                BEFORE UPDATE OR DELETE ON replay_dataset_manifests
                FOR EACH ROW EXECUTE FUNCTION protect_replay_dataset_manifest()
                """);
        statement.execute("""
                CREATE TRIGGER trg_replay_dataset_results_immutable
                BEFORE UPDATE OR DELETE ON replay_dataset_results
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
    }
}
