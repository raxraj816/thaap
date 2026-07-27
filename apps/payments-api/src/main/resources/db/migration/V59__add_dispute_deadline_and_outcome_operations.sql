CREATE TABLE dispute_reason_deadline_definitions (
    reason_code VARCHAR(64) NOT NULL,
    catalogue_version VARCHAR(32) NOT NULL,
    response_days INTEGER NOT NULL,
    require_authentication_or_device BOOLEAN NOT NULL,
    require_delivery_confirmation BOOLEAN NOT NULL,
    require_customer_communication BOOLEAN NOT NULL,
    require_refund_history BOOLEAN NOT NULL,
    CONSTRAINT pk_dispute_reason_deadline_definitions PRIMARY KEY (reason_code),
    CONSTRAINT ck_dispute_reason_deadline_version CHECK (catalogue_version = 'dispute_deadlines_v1'),
    CONSTRAINT ck_dispute_reason_deadline_days CHECK (response_days BETWEEN 1 AND 30)
);

INSERT INTO dispute_reason_deadline_definitions VALUES
    ('FRAUDULENT_STOLEN_CREDENTIAL', 'dispute_deadlines_v1', 7, TRUE, FALSE, FALSE, FALSE),
    ('FRAUDULENT_ACCOUNT_TAKEOVER', 'dispute_deadlines_v1', 7, TRUE, FALSE, FALSE, FALSE),
    ('FRAUDULENT_CARD_TESTING', 'dispute_deadlines_v1', 7, TRUE, FALSE, FALSE, FALSE),
    ('FRAUDULENT_FIRST_PARTY_MISUSE', 'dispute_deadlines_v1', 7, FALSE, FALSE, TRUE, FALSE),
    ('PRODUCT_NOT_RECEIVED', 'dispute_deadlines_v1', 14, FALSE, TRUE, TRUE, FALSE),
    ('DUPLICATE', 'dispute_deadlines_v1', 10, FALSE, FALSE, FALSE, TRUE),
    ('CREDIT_NOT_PROCESSED', 'dispute_deadlines_v1', 10, FALSE, FALSE, FALSE, TRUE),
    ('GENERAL', 'dispute_deadlines_v1', 10, FALSE, FALSE, TRUE, FALSE);

CREATE TABLE dispute_workflows (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    chargeback_id UUID NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    deadline_catalogue_version VARCHAR(32) NOT NULL,
    due_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    readiness_status VARCHAR(16) NOT NULL,
    missing_requirements VARCHAR(256) NOT NULL,
    latest_evidence_version_id UUID,
    submitted_evidence_version_id UUID,
    terminal_outcome_id UUID,
    projection_version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    expired_at TIMESTAMP(6) WITH TIME ZONE,
    resolved_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_dispute_workflows PRIMARY KEY (id),
    CONSTRAINT uk_dispute_workflows_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_dispute_workflows_chargeback UNIQUE (merchant_id, chargeback_id),
    CONSTRAINT fk_dispute_workflows_chargeback FOREIGN KEY (merchant_id, chargeback_id)
        REFERENCES chargebacks (merchant_id, id),
    CONSTRAINT fk_dispute_workflows_reason FOREIGN KEY (reason_code)
        REFERENCES dispute_reason_deadline_definitions (reason_code),
    CONSTRAINT ck_dispute_workflows_version CHECK (deadline_catalogue_version = 'dispute_deadlines_v1'),
    CONSTRAINT ck_dispute_workflows_status CHECK
        (lifecycle_status IN ('DRAFT', 'READY', 'SUBMITTED', 'EXPIRED', 'WON', 'LOST')),
    CONSTRAINT ck_dispute_workflows_readiness CHECK
        (readiness_status IN ('INCOMPLETE', 'READY')),
    CONSTRAINT ck_dispute_workflows_time CHECK (created_at <= updated_at),
    CONSTRAINT ck_dispute_workflows_terminal CHECK (
        (lifecycle_status IN ('DRAFT', 'READY') AND submitted_evidence_version_id IS NULL
            AND terminal_outcome_id IS NULL AND submitted_at IS NULL AND expired_at IS NULL
            AND resolved_at IS NULL)
        OR (lifecycle_status = 'SUBMITTED' AND submitted_evidence_version_id IS NOT NULL
            AND terminal_outcome_id IS NULL AND submitted_at IS NOT NULL AND expired_at IS NULL
            AND resolved_at IS NULL)
        OR (lifecycle_status = 'EXPIRED' AND submitted_evidence_version_id IS NULL
            AND terminal_outcome_id IS NULL AND submitted_at IS NULL AND expired_at IS NOT NULL
            AND resolved_at IS NULL)
        OR (lifecycle_status IN ('WON', 'LOST') AND submitted_evidence_version_id IS NOT NULL
            AND terminal_outcome_id IS NOT NULL AND submitted_at IS NOT NULL AND expired_at IS NULL
            AND resolved_at IS NOT NULL))
);

CREATE TABLE dispute_evidence_versions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    dispute_workflow_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    avs_result VARCHAR(32) NOT NULL,
    cvc_result VARCHAR(32) NOT NULL,
    three_ds_authenticated BOOLEAN NOT NULL,
    delivery_confirmed BOOLEAN NOT NULL,
    customer_communication_present BOOLEAN NOT NULL,
    refund_history_present BOOLEAN NOT NULL,
    device_continuity BOOLEAN NOT NULL,
    canonical_evidence_json VARCHAR(1024) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_dispute_evidence_versions PRIMARY KEY (id),
    CONSTRAINT uk_dispute_evidence_versions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_dispute_evidence_versions_number UNIQUE
        (merchant_id, dispute_workflow_id, version_number),
    CONSTRAINT fk_dispute_evidence_versions_workflow FOREIGN KEY (merchant_id, dispute_workflow_id)
        REFERENCES dispute_workflows (merchant_id, id),
    CONSTRAINT fk_dispute_evidence_versions_actor FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_dispute_evidence_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_dispute_evidence_versions_avs CHECK (avs_result IN ('MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    CONSTRAINT ck_dispute_evidence_versions_cvc CHECK (cvc_result IN ('MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    CONSTRAINT ck_dispute_evidence_versions_json CHECK
        (CHAR_LENGTH(canonical_evidence_json) BETWEEN 2 AND 1024 AND CHAR_LENGTH(evidence_digest) = 64)
);

CREATE TABLE dispute_outcomes (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    dispute_workflow_id UUID NOT NULL,
    submitted_evidence_version_id UUID NOT NULL,
    external_event_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    source_attestation VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_dispute_outcomes PRIMARY KEY (id),
    CONSTRAINT uk_dispute_outcomes_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_dispute_outcomes_event UNIQUE (merchant_id, external_event_key),
    CONSTRAINT uk_dispute_outcomes_workflow UNIQUE (merchant_id, dispute_workflow_id),
    CONSTRAINT fk_dispute_outcomes_workflow FOREIGN KEY (merchant_id, dispute_workflow_id)
        REFERENCES dispute_workflows (merchant_id, id),
    CONSTRAINT fk_dispute_outcomes_evidence FOREIGN KEY (merchant_id, submitted_evidence_version_id)
        REFERENCES dispute_evidence_versions (merchant_id, id),
    CONSTRAINT ck_dispute_outcomes_digest CHECK (CHAR_LENGTH(request_digest) = 64),
    CONSTRAINT ck_dispute_outcomes_value CHECK (outcome IN ('WON', 'LOST')),
    CONSTRAINT ck_dispute_outcomes_source CHECK (source_attestation = 'TRUSTED_SIMULATOR_OUTCOME'),
    CONSTRAINT ck_dispute_outcomes_time CHECK (occurred_at <= received_at)
);

CREATE TABLE dispute_workflow_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    dispute_workflow_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    evidence_version_id UUID,
    outcome_id UUID,
    actor_operator_id UUID,
    source VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_dispute_workflow_events PRIMARY KEY (id),
    CONSTRAINT uk_dispute_workflow_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_dispute_workflow_events_workflow FOREIGN KEY (merchant_id, dispute_workflow_id)
        REFERENCES dispute_workflows (merchant_id, id),
    CONSTRAINT fk_dispute_workflow_events_evidence FOREIGN KEY (merchant_id, evidence_version_id)
        REFERENCES dispute_evidence_versions (merchant_id, id),
    CONSTRAINT fk_dispute_workflow_events_outcome FOREIGN KEY (merchant_id, outcome_id)
        REFERENCES dispute_outcomes (merchant_id, id),
    CONSTRAINT fk_dispute_workflow_events_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_dispute_workflow_events_type CHECK
        (event_type IN ('WORKFLOW_CREATED', 'EVIDENCE_REVISED', 'SUBMITTED', 'EXPIRED',
                        'OUTCOME_WON', 'OUTCOME_LOST')),
    CONSTRAINT ck_dispute_workflow_events_source CHECK (
        (source = 'SYSTEM' AND actor_operator_id IS NULL AND event_type IN ('WORKFLOW_CREATED', 'EXPIRED'))
        OR (source = 'RISK_OPERATOR' AND actor_operator_id IS NOT NULL
            AND event_type IN ('EVIDENCE_REVISED', 'SUBMITTED'))
        OR (source = 'SIMULATOR' AND actor_operator_id IS NULL
            AND event_type IN ('OUTCOME_WON', 'OUTCOME_LOST')))
);

ALTER TABLE dispute_workflows ADD CONSTRAINT fk_dispute_workflows_latest_evidence FOREIGN KEY
    (merchant_id, latest_evidence_version_id) REFERENCES dispute_evidence_versions (merchant_id, id);
ALTER TABLE dispute_workflows ADD CONSTRAINT fk_dispute_workflows_submitted_evidence FOREIGN KEY
    (merchant_id, submitted_evidence_version_id) REFERENCES dispute_evidence_versions (merchant_id, id);
ALTER TABLE dispute_workflows ADD CONSTRAINT fk_dispute_workflows_outcome FOREIGN KEY
    (merchant_id, terminal_outcome_id) REFERENCES dispute_outcomes (merchant_id, id);

CREATE INDEX idx_dispute_workflows_queue
    ON dispute_workflows (merchant_id, lifecycle_status, due_at, id);
CREATE INDEX idx_dispute_workflows_expiry
    ON dispute_workflows (lifecycle_status, due_at, id);
CREATE INDEX idx_dispute_evidence_versions_history
    ON dispute_evidence_versions (merchant_id, dispute_workflow_id, version_number);
CREATE INDEX idx_dispute_workflow_events_history
    ON dispute_workflow_events (merchant_id, dispute_workflow_id, occurred_at, id);
