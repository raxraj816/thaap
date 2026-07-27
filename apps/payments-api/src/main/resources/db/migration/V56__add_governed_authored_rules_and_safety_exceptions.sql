ALTER TABLE risk_simulation_runs DROP CONSTRAINT ck_risk_simulation_runs_candidate_schema;
ALTER TABLE risk_simulation_runs ADD CONSTRAINT ck_risk_simulation_runs_candidate_schema CHECK
    (candidate_schema_version IN
        ('merchant_risk_policy_v1', 'authored_rule_v1', 'risk_safety_exception_v1'));

CREATE TABLE authored_rule_versions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    version_number INTEGER NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    canonical_rule_json TEXT NOT NULL,
    rule_digest CHAR(64) NOT NULL,
    candidate_action VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    feature_catalogue_version VARCHAR(32) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    projection_version BIGINT NOT NULL DEFAULT 0,
    created_by_operator_id UUID NOT NULL,
    approved_by_operator_id UUID,
    activation_simulation_run_id UUID,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    approved_at TIMESTAMP(6) WITH TIME ZONE,
    activated_at TIMESTAMP(6) WITH TIME ZONE,
    retired_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_authored_rule_versions PRIMARY KEY (id),
    CONSTRAINT uk_authored_rule_versions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_authored_rule_versions_code_version UNIQUE
        (merchant_id, rule_code, version_number),
    CONSTRAINT fk_authored_rule_versions_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_authored_rule_versions_creator FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_authored_rule_versions_approver FOREIGN KEY (merchant_id, approved_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_authored_rule_versions_simulation FOREIGN KEY (merchant_id, activation_simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT ck_authored_rule_versions_code CHECK (CHAR_LENGTH(rule_code) BETWEEN 3 AND 64),
    CONSTRAINT ck_authored_rule_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_authored_rule_versions_schema CHECK (schema_version = 'authored_rule_v1'),
    CONSTRAINT ck_authored_rule_versions_json CHECK (CHAR_LENGTH(canonical_rule_json) BETWEEN 2 AND 8192),
    CONSTRAINT ck_authored_rule_versions_digest CHECK (CHAR_LENGTH(rule_digest) = 64),
    CONSTRAINT ck_authored_rule_versions_action CHECK
        (candidate_action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')),
    CONSTRAINT ck_authored_rule_versions_reason CHECK
        (reason_code IN ('AUTHORED_RULE_MATCH')),
    CONSTRAINT ck_authored_rule_versions_catalogue CHECK (feature_catalogue_version = 'v1'),
    CONSTRAINT ck_authored_rule_versions_status CHECK
        (lifecycle_status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_authored_rule_versions_lifecycle CHECK (
        (lifecycle_status = 'DRAFT' AND submitted_at IS NULL AND approved_by_operator_id IS NULL
            AND approved_at IS NULL AND activated_at IS NULL AND retired_at IS NULL
            AND activation_simulation_run_id IS NULL)
        OR (lifecycle_status = 'SUBMITTED' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NULL AND approved_at IS NULL
            AND activated_at IS NULL AND retired_at IS NULL AND activation_simulation_run_id IS NULL)
        OR (lifecycle_status = 'APPROVED' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL
            AND activated_at IS NULL AND retired_at IS NULL AND activation_simulation_run_id IS NULL)
        OR (lifecycle_status = 'ACTIVE' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL
            AND activated_at IS NOT NULL AND retired_at IS NULL
            AND activation_simulation_run_id IS NOT NULL)
        OR (lifecycle_status = 'RETIRED' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL
            AND retired_at IS NOT NULL))
);

CREATE TABLE authored_rule_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    rule_version_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    actor_operator_id UUID NOT NULL,
    simulation_run_id UUID,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_authored_rule_events PRIMARY KEY (id),
    CONSTRAINT uk_authored_rule_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_authored_rule_events_version FOREIGN KEY (merchant_id, rule_version_id)
        REFERENCES authored_rule_versions (merchant_id, id),
    CONSTRAINT fk_authored_rule_events_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_authored_rule_events_simulation FOREIGN KEY (merchant_id, simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT ck_authored_rule_events_type CHECK
        (event_type IN ('CREATED', 'SUBMITTED', 'APPROVED', 'ACTIVATED', 'RETIRED', 'ROLLBACK_ACTIVATED')),
    CONSTRAINT ck_authored_rule_events_simulation CHECK
        ((event_type IN ('ACTIVATED', 'ROLLBACK_ACTIVATED')) = (simulation_run_id IS NOT NULL))
);

CREATE TABLE risk_safety_exception_versions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    exception_code VARCHAR(64) NOT NULL,
    version_number INTEGER NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    source_rule_version_id UUID NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_identity_hmac CHAR(64) NOT NULL,
    subject_hmac_key_version VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    valid_from TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    canonical_exception_json TEXT NOT NULL,
    exception_digest CHAR(64) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    projection_version BIGINT NOT NULL DEFAULT 0,
    created_by_operator_id UUID NOT NULL,
    approved_by_operator_id UUID,
    activation_simulation_run_id UUID,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    approved_at TIMESTAMP(6) WITH TIME ZONE,
    activated_at TIMESTAMP(6) WITH TIME ZONE,
    retired_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_risk_safety_exception_versions PRIMARY KEY (id),
    CONSTRAINT uk_risk_safety_exception_versions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_safety_exception_versions_code_version UNIQUE
        (merchant_id, exception_code, version_number),
    CONSTRAINT fk_risk_safety_exception_versions_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_risk_safety_exception_versions_source FOREIGN KEY (merchant_id, source_rule_version_id)
        REFERENCES authored_rule_versions (merchant_id, id),
    CONSTRAINT fk_risk_safety_exception_versions_creator FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_risk_safety_exception_versions_approver FOREIGN KEY (merchant_id, approved_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_risk_safety_exception_versions_simulation FOREIGN KEY (merchant_id, activation_simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT ck_risk_safety_exception_versions_code CHECK
        (CHAR_LENGTH(exception_code) BETWEEN 3 AND 64),
    CONSTRAINT ck_risk_safety_exception_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_risk_safety_exception_versions_schema CHECK
        (schema_version = 'risk_safety_exception_v1'),
    CONSTRAINT ck_risk_safety_exception_versions_subject CHECK
        (subject_type = 'CARD_FINGERPRINT' AND CHAR_LENGTH(subject_identity_hmac) = 64
         AND CHAR_LENGTH(subject_hmac_key_version) BETWEEN 1 AND 32),
    CONSTRAINT ck_risk_safety_exception_versions_reason CHECK
        (reason_code IN ('TRUSTED_CUSTOMER_REMEDIATION', 'FALSE_POSITIVE_REMEDIATION')),
    CONSTRAINT ck_risk_safety_exception_versions_time CHECK
        (valid_from < expires_at AND expires_at <= valid_from + INTERVAL '30' DAY),
    CONSTRAINT ck_risk_safety_exception_versions_json CHECK
        (CHAR_LENGTH(canonical_exception_json) BETWEEN 2 AND 8192),
    CONSTRAINT ck_risk_safety_exception_versions_digest CHECK (CHAR_LENGTH(exception_digest) = 64),
    CONSTRAINT ck_risk_safety_exception_versions_status CHECK
        (lifecycle_status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_risk_safety_exception_versions_lifecycle CHECK (
        (lifecycle_status = 'DRAFT' AND submitted_at IS NULL AND approved_by_operator_id IS NULL
            AND approved_at IS NULL AND activated_at IS NULL AND retired_at IS NULL
            AND activation_simulation_run_id IS NULL)
        OR (lifecycle_status = 'SUBMITTED' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NULL AND approved_at IS NULL
            AND activated_at IS NULL AND retired_at IS NULL AND activation_simulation_run_id IS NULL)
        OR (lifecycle_status = 'APPROVED' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL
            AND activated_at IS NULL AND retired_at IS NULL AND activation_simulation_run_id IS NULL)
        OR (lifecycle_status = 'ACTIVE' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL
            AND activated_at IS NOT NULL AND retired_at IS NULL
            AND activation_simulation_run_id IS NOT NULL)
        OR (lifecycle_status = 'RETIRED' AND submitted_at IS NOT NULL
            AND approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL
            AND retired_at IS NOT NULL))
);

CREATE TABLE risk_safety_exception_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    exception_version_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    actor_operator_id UUID NOT NULL,
    simulation_run_id UUID,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_safety_exception_events PRIMARY KEY (id),
    CONSTRAINT uk_risk_safety_exception_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_risk_safety_exception_events_version FOREIGN KEY (merchant_id, exception_version_id)
        REFERENCES risk_safety_exception_versions (merchant_id, id),
    CONSTRAINT fk_risk_safety_exception_events_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_risk_safety_exception_events_simulation FOREIGN KEY (merchant_id, simulation_run_id)
        REFERENCES risk_simulation_runs (merchant_id, id),
    CONSTRAINT ck_risk_safety_exception_events_type CHECK
        (event_type IN ('CREATED', 'SUBMITTED', 'APPROVED', 'ACTIVATED', 'RETIRED')),
    CONSTRAINT ck_risk_safety_exception_events_simulation CHECK
        ((event_type = 'ACTIVATED') = (simulation_run_id IS NOT NULL))
);

CREATE TABLE authored_rule_evaluations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    rule_version_id UUID NOT NULL,
    rule_digest CHAR(64) NOT NULL,
    matched BOOLEAN NOT NULL,
    candidate_action VARCHAR(16) NOT NULL,
    exception_disposition VARCHAR(32) NOT NULL,
    applied_exception_version_id UUID,
    effective_action VARCHAR(16),
    evaluated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    CONSTRAINT pk_authored_rule_evaluations PRIMARY KEY (id),
    CONSTRAINT uk_authored_rule_evaluations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_authored_rule_evaluations_attempt_rule UNIQUE
        (merchant_id, payment_attempt_id, rule_version_id),
    CONSTRAINT fk_authored_rule_evaluations_attempt FOREIGN KEY
        (merchant_id, payment_attempt_id, traffic_origin)
        REFERENCES payment_attempts (merchant_id, id, traffic_origin),
    CONSTRAINT fk_authored_rule_evaluations_decision FOREIGN KEY
        (merchant_id, fraud_decision_id, payment_attempt_id, traffic_origin)
        REFERENCES fraud_decisions (merchant_id, id, payment_attempt_id, traffic_origin),
    CONSTRAINT fk_authored_rule_evaluations_rule FOREIGN KEY (merchant_id, rule_version_id)
        REFERENCES authored_rule_versions (merchant_id, id),
    CONSTRAINT fk_authored_rule_evaluations_exception FOREIGN KEY
        (merchant_id, applied_exception_version_id)
        REFERENCES risk_safety_exception_versions (merchant_id, id),
    CONSTRAINT ck_authored_rule_evaluations_digest CHECK (CHAR_LENGTH(rule_digest) = 64),
    CONSTRAINT ck_authored_rule_evaluations_action CHECK
        (candidate_action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')
         AND (effective_action IS NULL OR effective_action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS'))),
    CONSTRAINT ck_authored_rule_evaluations_exception CHECK (
        (exception_disposition = 'APPLIED' AND matched = TRUE
            AND applied_exception_version_id IS NOT NULL AND effective_action IS NULL)
        OR (exception_disposition IN ('NONE', 'NOT_APPLICABLE', 'EXPIRED', 'INVALID_EVIDENCE')
            AND applied_exception_version_id IS NULL
            AND ((matched = TRUE AND effective_action = candidate_action)
                 OR (matched = FALSE AND effective_action IS NULL))))
);

CREATE INDEX idx_authored_rule_versions_active
    ON authored_rule_versions (merchant_id, rule_code, lifecycle_status, version_number);
CREATE INDEX idx_authored_rule_events_history
    ON authored_rule_events (merchant_id, rule_version_id, occurred_at);
CREATE INDEX idx_risk_safety_exception_active
    ON risk_safety_exception_versions
        (merchant_id, source_rule_version_id, lifecycle_status, valid_from, expires_at);
CREATE INDEX idx_authored_rule_evaluations_decision
    ON authored_rule_evaluations (merchant_id, fraud_decision_id, rule_version_id);
