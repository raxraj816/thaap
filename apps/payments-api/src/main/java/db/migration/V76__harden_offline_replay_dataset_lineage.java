package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Completes the immutable, point-in-time and restart-safe lineage boundary for
 * offline replay datasets.
 */
public final class V76__harden_offline_replay_dataset_lineage extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        try (Statement statement = connection.createStatement()) {
            // V74 intentionally shipped only the substrate. Refuse an ambiguous
            // in-place hardening when a deployment wrote manifests before this
            // lineage contract existed: there is no honest value with which to
            // backfill creator, cohort, or canonical manifest evidence.
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT COUNT(*) FROM replay_dataset_manifests")) {
                resultSet.next();
                if (resultSet.getLong(1) != 0) {
                    throw new SQLException(
                            "V76 requires replay_dataset_manifests to be empty before hardening");
                }
            }
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN created_by_operator_id UUID NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN cohort_start TIMESTAMP(6) WITH TIME ZONE NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN cohort_end TIMESTAMP(6) WITH TIME ZONE NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN event_cutoff_manifest_sha256 VARCHAR(64) NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN dataset_manifest_sha256 VARCHAR(64) NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN label_resolution_policy VARCHAR(32) NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN source_contract_version VARCHAR(32) NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN worker_schema_version VARCHAR(32) NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN compatibility_simulation_run_id UUID
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN checkpoint_decision_created_at TIMESTAMP(6) WITH TIME ZONE
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN checkpoint_attempt_id UUID
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN processed_count BIGINT NOT NULL DEFAULT 0
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN corrupt_lineage_count BIGINT NOT NULL DEFAULT 0
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN population_sha256 VARCHAR(64)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN compatibility_sha256 VARCHAR(64)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD COLUMN compatibility_failure_code VARCHAR(48)
                    """);

            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT uk_replay_dataset_manifests_merchant_id_id
                    UNIQUE (merchant_id, id)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT fk_replay_dataset_manifests_creator
                    FOREIGN KEY (merchant_id, created_by_operator_id)
                    REFERENCES risk_operators (merchant_id, id)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT fk_replay_dataset_manifests_simulation
                    FOREIGN KEY (merchant_id, compatibility_simulation_run_id)
                    REFERENCES risk_simulation_runs (merchant_id, id)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_results
                    ADD CONSTRAINT fk_replay_dataset_results_tenant_manifest
                    FOREIGN KEY (merchant_id, replay_dataset_id)
                    REFERENCES replay_dataset_manifests (merchant_id, id)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT ck_replay_dataset_manifests_point_in_time CHECK (
                        cohort_start < cohort_end
                        AND cohort_end <= label_availability_cutoff
                        AND label_resolution_policy = 'CURRENT_AT_CUTOFF'
                        AND source_contract_version = 'historical_simulation_v1'
                        AND worker_schema_version = 'offline_replay_v1'
                        AND ((checkpoint_decision_created_at IS NULL)
                            = (checkpoint_attempt_id IS NULL)))
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT ck_replay_dataset_manifests_extended_counts CHECK (
                        processed_count >= 0 AND corrupt_lineage_count >= 0
                        AND processed_count = eligible_count)
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT ck_replay_dataset_manifests_extended_digests CHECK (
                        LENGTH(event_cutoff_manifest_sha256) = 64
                        AND LENGTH(dataset_manifest_sha256) = 64
                        AND (population_sha256 IS NULL OR LENGTH(population_sha256) = 64)
                        AND (compatibility_sha256 IS NULL OR LENGTH(compatibility_sha256) = 64))
                    """);
            statement.execute("""
                    ALTER TABLE replay_dataset_manifests
                    ADD CONSTRAINT ck_replay_dataset_manifests_compatibility_result CHECK (
                        (compatibility_status = 'PENDING'
                            AND compatibility_sha256 IS NULL
                            AND compatibility_failure_code IS NULL)
                        OR (compatibility_status = 'PASSED'
                            AND compatibility_sha256 IS NOT NULL
                            AND compatibility_failure_code IS NULL)
                        OR (compatibility_status = 'FAILED'
                            AND compatibility_sha256 IS NOT NULL
                            AND compatibility_failure_code IS NOT NULL))
                    """);

            statement.execute("""
                    CREATE TABLE replay_dataset_chunks (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        replay_dataset_id UUID NOT NULL,
                        chunk_ordinal BIGINT NOT NULL,
                        fencing_epoch BIGINT NOT NULL,
                        processed_count BIGINT NOT NULL,
                        included_count BIGINT NOT NULL,
                        unlabeled_count BIGINT NOT NULL,
                        contradictory_label_count BIGINT NOT NULL,
                        superseded_label_count BIGINT NOT NULL,
                        synthetic_excluded_count BIGINT NOT NULL,
                        unsupported_version_count BIGINT NOT NULL,
                        missing_feature_count BIGINT NOT NULL,
                        corrupt_lineage_count BIGINT NOT NULL,
                        chunk_population_sha256 VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_replay_dataset_chunks PRIMARY KEY (id),
                        CONSTRAINT uk_replay_dataset_chunks_ordinal UNIQUE
                            (merchant_id, replay_dataset_id, chunk_ordinal),
                        CONSTRAINT fk_replay_dataset_chunks_manifest FOREIGN KEY
                            (merchant_id, replay_dataset_id)
                            REFERENCES replay_dataset_manifests (merchant_id, id),
                        CONSTRAINT ck_replay_dataset_chunks_ordinal CHECK (chunk_ordinal > 0),
                        CONSTRAINT ck_replay_dataset_chunks_fence CHECK (fencing_epoch > 0),
                        CONSTRAINT ck_replay_dataset_chunks_counts CHECK (
                            processed_count > 0 AND included_count >= 0
                            AND unlabeled_count >= 0 AND contradictory_label_count >= 0
                            AND superseded_label_count >= 0
                            AND synthetic_excluded_count >= 0
                            AND unsupported_version_count >= 0
                            AND missing_feature_count >= 0
                            AND corrupt_lineage_count >= 0
                            AND included_count <= processed_count),
                        CONSTRAINT ck_replay_dataset_chunks_digest CHECK
                            (LENGTH(chunk_population_sha256) = 64)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE replay_dataset_chunk_results (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        replay_dataset_id UUID NOT NULL,
                        chunk_ordinal BIGINT NOT NULL,
                        metric_code VARCHAR(48) NOT NULL,
                        bucket_code VARCHAR(64) NOT NULL,
                        population_count BIGINT NOT NULL,
                        amount_minor BIGINT,
                        result_sha256 VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_replay_dataset_chunk_results PRIMARY KEY (id),
                        CONSTRAINT uk_replay_dataset_chunk_results_bucket UNIQUE
                            (merchant_id, replay_dataset_id, chunk_ordinal,
                             metric_code, bucket_code),
                        CONSTRAINT fk_replay_dataset_chunk_results_chunk FOREIGN KEY
                            (merchant_id, replay_dataset_id, chunk_ordinal)
                            REFERENCES replay_dataset_chunks
                                (merchant_id, replay_dataset_id, chunk_ordinal),
                        CONSTRAINT ck_replay_dataset_chunk_results_metric CHECK (metric_code IN
                            ('LABEL_OUTCOME', 'ORIGINAL_ACTION', 'TRAFFIC_ORIGIN',
                             'FEATURE_COVERAGE')),
                        CONSTRAINT ck_replay_dataset_chunk_results_values CHECK (
                            population_count > 0 AND (amount_minor IS NULL OR amount_minor >= 0)),
                        CONSTRAINT ck_replay_dataset_chunk_results_digest CHECK
                            (LENGTH(result_sha256) = 64)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE replay_dataset_item_evidence (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        replay_dataset_id UUID NOT NULL,
                        item_ordinal BIGINT NOT NULL,
                        chunk_ordinal BIGINT NOT NULL,
                        membership_sha256 VARCHAR(64) NOT NULL,
                        item_sha256 VARCHAR(64) NOT NULL,
                        compatibility_item_sha256 VARCHAR(64),
                        exclusion_code VARCHAR(32),
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_replay_dataset_item_evidence PRIMARY KEY (id),
                        CONSTRAINT uk_replay_dataset_item_evidence_ordinal UNIQUE
                            (merchant_id, replay_dataset_id, item_ordinal),
                        CONSTRAINT fk_replay_dataset_item_evidence_chunk FOREIGN KEY
                            (merchant_id, replay_dataset_id, chunk_ordinal)
                            REFERENCES replay_dataset_chunks
                                (merchant_id, replay_dataset_id, chunk_ordinal),
                        CONSTRAINT ck_replay_dataset_item_evidence_ordinal CHECK (item_ordinal > 0),
                        CONSTRAINT ck_replay_dataset_item_evidence_digests CHECK (
                            LENGTH(membership_sha256) = 64
                            AND LENGTH(item_sha256) = 64
                            AND (compatibility_item_sha256 IS NULL
                                OR LENGTH(compatibility_item_sha256) = 64)),
                        CONSTRAINT ck_replay_dataset_item_evidence_exclusion CHECK
                            (exclusion_code IS NULL OR exclusion_code IN
                                ('SYNTHETIC_EXCLUDED', 'UNSUPPORTED_VERSION',
                                 'MISSING_FEATURE', 'CORRUPT_LINEAGE'))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE replay_dataset_compatibility_evidence (
                        id UUID NOT NULL,
                        merchant_id UUID NOT NULL,
                        replay_dataset_id UUID NOT NULL,
                        item_ordinal BIGINT NOT NULL,
                        invariant_family VARCHAR(32) NOT NULL,
                        result_status VARCHAR(8) NOT NULL,
                        expected_sha256 VARCHAR(64),
                        actual_sha256 VARCHAR(64),
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_replay_dataset_compatibility_evidence PRIMARY KEY (id),
                        CONSTRAINT uk_replay_dataset_compatibility_evidence_family UNIQUE
                            (merchant_id, replay_dataset_id, item_ordinal, invariant_family),
                        CONSTRAINT fk_replay_dataset_compatibility_evidence_item FOREIGN KEY
                            (merchant_id, replay_dataset_id, item_ordinal)
                            REFERENCES replay_dataset_item_evidence
                                (merchant_id, replay_dataset_id, item_ordinal),
                        CONSTRAINT ck_replay_dataset_compatibility_evidence_family CHECK
                            (invariant_family IN
                                ('POPULATION', 'ELIGIBILITY', 'ORIGINAL_ACTION',
                                 'LABEL_OUTCOME', 'MONEY_SUMMARY', 'TRAFFIC_ORIGIN',
                                 'CANDIDATE_INPUT_LINEAGE')),
                        CONSTRAINT ck_replay_dataset_compatibility_evidence_status CHECK
                            (result_status IN ('PASS', 'FAIL')),
                        CONSTRAINT ck_replay_dataset_compatibility_evidence_digests CHECK (
                            (expected_sha256 IS NULL AND actual_sha256 IS NULL)
                            OR (LENGTH(expected_sha256) = 64
                                AND LENGTH(actual_sha256) = 64))
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_chunks_manifest
                    ON replay_dataset_chunks
                        (merchant_id, replay_dataset_id, chunk_ordinal)
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_item_evidence_manifest
                    ON replay_dataset_item_evidence
                        (merchant_id, replay_dataset_id, item_ordinal)
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_compatibility_manifest
                    ON replay_dataset_compatibility_evidence
                        (merchant_id, replay_dataset_id, invariant_family, result_status)
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_source_query
                    ON fraud_decisions (merchant_id, created_at, payment_attempt_id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_replay_dataset_label_cutoff
                    ON fraud_labels
                        (merchant_id, payment_attempt_id, available_at, id)
                    """);

            if (postgresql) {
                installPostgreSqlGuards(statement);
            }
        }
    }

    private void installPostgreSqlGuards(Statement statement) throws SQLException {
        statement.execute("""
                ALTER TABLE replay_dataset_manifests
                ADD CONSTRAINT ck_replay_dataset_manifests_extended_digest_lower_hex CHECK (
                    event_cutoff_manifest_sha256 ~ '^[0-9a-f]{64}$'
                    AND dataset_manifest_sha256 ~ '^[0-9a-f]{64}$'
                    AND (population_sha256 IS NULL
                        OR population_sha256 ~ '^[0-9a-f]{64}$')
                    AND (compatibility_sha256 IS NULL
                        OR compatibility_sha256 ~ '^[0-9a-f]{64}$'))
                """);
        statement.execute("""
                ALTER TABLE replay_dataset_chunks
                ADD CONSTRAINT ck_replay_dataset_chunks_digest_lower_hex
                CHECK (chunk_population_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE replay_dataset_chunk_results
                ADD CONSTRAINT ck_replay_dataset_chunk_results_digest_lower_hex
                CHECK (result_sha256 ~ '^[0-9a-f]{64}$')
                """);
        statement.execute("""
                ALTER TABLE replay_dataset_item_evidence
                ADD CONSTRAINT ck_replay_dataset_item_evidence_digest_lower_hex CHECK (
                    membership_sha256 ~ '^[0-9a-f]{64}$'
                    AND item_sha256 ~ '^[0-9a-f]{64}$'
                    AND (compatibility_item_sha256 IS NULL
                        OR compatibility_item_sha256 ~ '^[0-9a-f]{64}$'))
                """);
        statement.execute("""
                ALTER TABLE replay_dataset_compatibility_evidence
                ADD CONSTRAINT ck_replay_dataset_compatibility_digest_lower_hex CHECK (
                    (expected_sha256 IS NULL AND actual_sha256 IS NULL)
                    OR (expected_sha256 ~ '^[0-9a-f]{64}$'
                        AND actual_sha256 ~ '^[0-9a-f]{64}$'))
                """);
        statement.execute("""
                CREATE OR REPLACE FUNCTION protect_replay_dataset_manifest()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF TG_OP = 'DELETE'
                       OR OLD.lifecycle_status IN ('SUCCEEDED', 'FAILED')
                       OR NEW.id <> OLD.id
                       OR NEW.merchant_id <> OLD.merchant_id
                       OR NEW.created_by_operator_id <> OLD.created_by_operator_id
                       OR NEW.cutoff_manifest_id <> OLD.cutoff_manifest_id
                       OR NEW.projection_generation_id <> OLD.projection_generation_id
                       OR NEW.compatibility_simulation_run_id IS DISTINCT FROM
                            OLD.compatibility_simulation_run_id
                       OR NEW.cohort_start <> OLD.cohort_start
                       OR NEW.cohort_end <> OLD.cohort_end
                       OR NEW.label_availability_cutoff <> OLD.label_availability_cutoff
                       OR NEW.projection_version <> OLD.projection_version
                       OR NEW.evaluator_version <> OLD.evaluator_version
                       OR NEW.feature_catalogue_version <> OLD.feature_catalogue_version
                       OR NEW.fraud_taxonomy_version <> OLD.fraud_taxonomy_version
                       OR NEW.traffic_origin_policy <> OLD.traffic_origin_policy
                       OR NEW.label_resolution_policy <> OLD.label_resolution_policy
                       OR NEW.source_contract_version <> OLD.source_contract_version
                       OR NEW.worker_schema_version <> OLD.worker_schema_version
                       OR NEW.bootstrap_manifest_sha256 <> OLD.bootstrap_manifest_sha256
                       OR NEW.event_cutoff_manifest_sha256 <>
                            OLD.event_cutoff_manifest_sha256
                       OR NEW.query_sha256 <> OLD.query_sha256
                       OR NEW.dataset_manifest_sha256 <> OLD.dataset_manifest_sha256
                       OR NEW.created_at <> OLD.created_at
                       OR NEW.fencing_epoch < OLD.fencing_epoch
                       OR NEW.checkpoint_ordinal < OLD.checkpoint_ordinal
                       OR NEW.processed_count < OLD.processed_count
                       OR NEW.eligible_count < OLD.eligible_count
                       OR NEW.included_count < OLD.included_count
                       OR NEW.unlabeled_count < OLD.unlabeled_count
                       OR NEW.contradictory_label_count < OLD.contradictory_label_count
                       OR NEW.superseded_label_count < OLD.superseded_label_count
                       OR NEW.synthetic_excluded_count < OLD.synthetic_excluded_count
                       OR NEW.unsupported_event_count < OLD.unsupported_event_count
                       OR NEW.missing_feature_count < OLD.missing_feature_count
                       OR NEW.corrupt_lineage_count < OLD.corrupt_lineage_count
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
                CREATE TRIGGER trg_replay_dataset_chunks_immutable
                BEFORE UPDATE OR DELETE ON replay_dataset_chunks
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_replay_dataset_chunk_results_immutable
                BEFORE UPDATE OR DELETE ON replay_dataset_chunk_results
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_replay_dataset_item_evidence_immutable
                BEFORE UPDATE OR DELETE ON replay_dataset_item_evidence
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
        statement.execute("""
                CREATE TRIGGER trg_replay_dataset_compatibility_evidence_immutable
                BEFORE UPDATE OR DELETE ON replay_dataset_compatibility_evidence
                FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                """);
    }
}
