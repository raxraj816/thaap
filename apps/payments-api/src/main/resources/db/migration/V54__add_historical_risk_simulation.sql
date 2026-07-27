-- Spring Batch 6.0.3 JDBC metadata schema, installed by Flyway so API and
-- worker processes share one explicit, versioned restart boundary.
CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_NAME VARCHAR(100) NOT NULL,
    JOB_KEY VARCHAR(32) NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_INSTANCE_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE (JOB_INSTANCE_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL,
    PARAMETER_NAME VARCHAR(100) NOT NULL,
    PARAMETER_TYPE VARCHAR(100) NOT NULL,
    PARAMETER_VALUE VARCHAR(2500),
    IDENTIFYING CHAR(1) NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT NOT NULL,
    STEP_NAME VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    COMMIT_COUNT BIGINT,
    READ_COUNT BIGINT,
    FILTER_COUNT BIGINT,
    WRITE_COUNT BIGINT,
    READ_SKIP_COUNT BIGINT,
    WRITE_SKIP_COUNT BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT BIGINT,
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION (STEP_EXECUTION_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

CREATE TABLE risk_simulation_runs (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    created_by_operator_id UUID NOT NULL,
    candidate_type VARCHAR(32) NOT NULL,
    candidate_version_id UUID NOT NULL,
    candidate_schema_version VARCHAR(32) NOT NULL,
    candidate_json TEXT NOT NULL,
    candidate_digest CHAR(64) NOT NULL,
    cohort_start TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    cohort_end TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    as_of TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin_policy VARCHAR(32) NOT NULL,
    label_cutoff TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    label_resolution_policy VARCHAR(32) NOT NULL,
    evaluator_version VARCHAR(32) NOT NULL,
    feature_catalogue_version VARCHAR(32) NOT NULL,
    rule_catalogue_version VARCHAR(32) NOT NULL,
    taxonomy_version VARCHAR(32) NOT NULL,
    query_digest CHAR(64) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    job_execution_id BIGINT,
    execution_token UUID NOT NULL,
    projection_version BIGINT NOT NULL DEFAULT 0,
    checkpoint_created_at TIMESTAMP(6) WITH TIME ZONE,
    checkpoint_attempt_id UUID,
    processed_count BIGINT NOT NULL DEFAULT 0,
    eligible_count BIGINT NOT NULL DEFAULT 0,
    excluded_count BIGINT NOT NULL DEFAULT 0,
    failure_stage VARCHAR(32),
    failure_code VARCHAR(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    queued_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    report_expires_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_risk_simulation_runs PRIMARY KEY (id),
    CONSTRAINT uk_risk_simulation_runs_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_simulation_runs_job_execution UNIQUE (job_execution_id),
    CONSTRAINT fk_risk_simulation_runs_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_risk_simulation_runs_creator FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_risk_simulation_runs_candidate_type CHECK
        (candidate_type IN ('MERCHANT_RISK_POLICY', 'AUTHORED_RULE', 'SAFETY_EXCEPTION')),
    CONSTRAINT ck_risk_simulation_runs_candidate_schema CHECK
        (candidate_schema_version IN ('merchant_risk_policy_v1')),
    CONSTRAINT ck_risk_simulation_runs_digest CHECK
        (CHAR_LENGTH(candidate_digest) = 64 AND CHAR_LENGTH(query_digest) = 64),
    CONSTRAINT ck_risk_simulation_runs_candidate_size CHECK
        (CHAR_LENGTH(candidate_json) BETWEEN 2 AND 32768),
    CONSTRAINT ck_risk_simulation_runs_origin CHECK
        (traffic_origin_policy IN ('LIVE_ONLY', 'INCLUDE_SYNTHETIC')),
    CONSTRAINT ck_risk_simulation_runs_label_policy CHECK
        (label_resolution_policy IN ('CURRENT_AT_CUTOFF')),
    CONSTRAINT ck_risk_simulation_runs_status CHECK
        (lifecycle_status IN ('DRAFT', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_risk_simulation_runs_checkpoint CHECK
        ((checkpoint_created_at IS NULL) = (checkpoint_attempt_id IS NULL)),
    CONSTRAINT ck_risk_simulation_runs_counts CHECK
        (processed_count >= 0 AND eligible_count >= 0 AND excluded_count >= 0
         AND processed_count = eligible_count + excluded_count),
    CONSTRAINT ck_risk_simulation_runs_time CHECK
        (cohort_start < cohort_end AND cohort_end <= as_of AND label_cutoff <= as_of
         AND created_at = queued_at AND started_at >= queued_at
         AND completed_at >= COALESCE(started_at, queued_at)
         AND report_expires_at >= completed_at),
    CONSTRAINT ck_risk_simulation_runs_terminal CHECK
        ((lifecycle_status IN ('DRAFT', 'QUEUED') AND started_at IS NULL
          AND completed_at IS NULL AND report_expires_at IS NULL)
         OR (lifecycle_status = 'RUNNING' AND started_at IS NOT NULL
             AND completed_at IS NULL AND report_expires_at IS NULL)
         OR (lifecycle_status = 'SUCCEEDED' AND started_at IS NOT NULL
             AND completed_at IS NOT NULL AND report_expires_at IS NOT NULL
             AND failure_stage IS NULL AND failure_code IS NULL)
         OR (lifecycle_status IN ('FAILED', 'CANCELED') AND completed_at IS NOT NULL
             AND report_expires_at IS NULL))
);

CREATE TABLE risk_simulation_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    simulation_run_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    actor_operator_id UUID,
    execution_token UUID,
    safe_reason_code VARCHAR(64),
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_simulation_events PRIMARY KEY (id),
    CONSTRAINT uk_risk_simulation_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_simulation_events_run_type UNIQUE (merchant_id, simulation_run_id, event_type),
    CONSTRAINT fk_risk_simulation_events_run FOREIGN KEY (merchant_id, simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT fk_risk_simulation_events_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_risk_simulation_events_type CHECK
        (event_type IN ('CREATED', 'QUEUED', 'STARTED', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_risk_simulation_events_actor CHECK
        ((event_type IN ('CREATED', 'QUEUED', 'CANCELED') AND actor_operator_id IS NOT NULL)
         OR (event_type IN ('STARTED', 'SUCCEEDED', 'FAILED') AND execution_token IS NOT NULL))
);

CREATE TABLE risk_simulation_results (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    simulation_run_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    decision_created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    original_action VARCHAR(16) NOT NULL,
    candidate_action VARCHAR(16),
    eligibility_status VARCHAR(16) NOT NULL,
    exclusion_reason VARCHAR(64),
    label_outcome VARCHAR(16) NOT NULL,
    protected_suppression_attempted BOOLEAN NOT NULL,
    authorization_amount BIGINT NOT NULL,
    capture_amount BIGINT NOT NULL,
    refund_amount BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_simulation_results PRIMARY KEY (id),
    CONSTRAINT uk_risk_simulation_results_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_simulation_results_run_attempt UNIQUE
        (merchant_id, simulation_run_id, payment_attempt_id),
    CONSTRAINT fk_risk_simulation_results_run FOREIGN KEY (merchant_id, simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT fk_risk_simulation_results_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT fk_risk_simulation_results_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT ck_risk_simulation_results_origin CHECK
        (traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_risk_simulation_results_action CHECK
        (original_action IN ('ALLOW', 'BLOCK', 'REQUEST_3DS', 'REVIEW')
         AND (candidate_action IS NULL OR candidate_action IN ('ALLOW', 'BLOCK', 'REQUEST_3DS', 'REVIEW'))),
    CONSTRAINT ck_risk_simulation_results_eligibility CHECK
        ((eligibility_status = 'ELIGIBLE' AND exclusion_reason IS NULL AND candidate_action IS NOT NULL)
         OR (eligibility_status = 'EXCLUDED' AND exclusion_reason IN
             ('MISSING_FEATURE', 'UNSUPPORTED_CATALOGUE', 'CORRUPT_LINEAGE')
             AND candidate_action IS NULL)),
    CONSTRAINT ck_risk_simulation_results_label CHECK
        (label_outcome IN ('FRAUD', 'LEGITIMATE', 'CONTRADICTORY', 'UNLABELED')),
    CONSTRAINT ck_risk_simulation_results_amounts CHECK
        (authorization_amount >= 0 AND capture_amount >= 0 AND refund_amount >= 0)
);

CREATE TABLE risk_simulation_reports (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    simulation_run_id UUID NOT NULL,
    canonical_report_json TEXT NOT NULL,
    report_digest CHAR(64) NOT NULL,
    processed_count BIGINT NOT NULL,
    eligible_count BIGINT NOT NULL,
    excluded_count BIGINT NOT NULL,
    protected_suppression_count BIGINT NOT NULL,
    blocking_data_quality_count BIGINT NOT NULL,
    labelled_count BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_simulation_reports PRIMARY KEY (id),
    CONSTRAINT uk_risk_simulation_reports_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_simulation_reports_run UNIQUE (merchant_id, simulation_run_id),
    CONSTRAINT fk_risk_simulation_reports_run FOREIGN KEY (merchant_id, simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT ck_risk_simulation_reports_digest CHECK (CHAR_LENGTH(report_digest) = 64),
    CONSTRAINT ck_risk_simulation_reports_counts CHECK
        (processed_count >= 0 AND eligible_count >= 0 AND excluded_count >= 0
         AND processed_count = eligible_count + excluded_count
         AND protected_suppression_count >= 0 AND blocking_data_quality_count >= 0
         AND labelled_count >= 0 AND labelled_count <= eligible_count),
    CONSTRAINT ck_risk_simulation_reports_time CHECK (created_at < expires_at)
);

CREATE TABLE risk_simulation_segments (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    simulation_run_id UUID NOT NULL,
    dimension_code VARCHAR(32) NOT NULL,
    segment_code VARCHAR(64) NOT NULL,
    population_count BIGINT NOT NULL,
    eligible_count BIGINT NOT NULL,
    transition_json TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_simulation_segments PRIMARY KEY (id),
    CONSTRAINT uk_risk_simulation_segments_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_simulation_segments_identity UNIQUE
        (merchant_id, simulation_run_id, dimension_code, segment_code),
    CONSTRAINT fk_risk_simulation_segments_run FOREIGN KEY (merchant_id, simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT ck_risk_simulation_segments_dimension CHECK
        (dimension_code IN ('TRAFFIC_ORIGIN')),
    CONSTRAINT ck_risk_simulation_segments_code CHECK
        (segment_code IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_risk_simulation_segments_population CHECK
        (population_count >= 10 AND eligible_count >= 0 AND eligible_count <= population_count)
);

CREATE INDEX idx_risk_simulation_runs_merchant_created
    ON risk_simulation_runs (merchant_id, created_at, id);
CREATE INDEX idx_risk_simulation_runs_status_queued
    ON risk_simulation_runs (lifecycle_status, queued_at, id);
CREATE INDEX idx_risk_simulation_results_run_order
    ON risk_simulation_results (merchant_id, simulation_run_id, decision_created_at, payment_attempt_id);
CREATE INDEX idx_risk_simulation_events_run_time
    ON risk_simulation_events (merchant_id, simulation_run_id, occurred_at, id);
