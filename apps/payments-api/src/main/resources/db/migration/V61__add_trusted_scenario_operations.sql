CREATE TABLE developer_scenario_runs (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    scenario_type VARCHAR(32) NOT NULL,
    catalogue_version VARCHAR(32) NOT NULL,
    seed_digest CHAR(64) NOT NULL,
    specification_json VARCHAR(1024) NOT NULL,
    specification_digest CHAR(64) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    total_steps INTEGER NOT NULL,
    next_step_index INTEGER NOT NULL,
    lease_owner UUID,
    lease_epoch BIGINT NOT NULL,
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    failure_code VARCHAR(32),
    report_json VARCHAR(8192),
    report_digest CHAR(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_developer_scenario_runs PRIMARY KEY (id),
    CONSTRAINT uk_developer_scenario_runs_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_developer_scenario_runs_specification UNIQUE (merchant_id, specification_digest),
    CONSTRAINT fk_developer_scenario_runs_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT ck_developer_scenario_runs_type CHECK
        (scenario_type IN ('APPROVED_PAYMENT', 'ISSUER_DECLINE', 'CARD_TESTING_BURST')),
    CONSTRAINT ck_developer_scenario_runs_catalogue CHECK
        (catalogue_version = 'developer_scenarios_v1'),
    CONSTRAINT ck_developer_scenario_runs_digest CHECK
        (CHAR_LENGTH(seed_digest) = 64 AND CHAR_LENGTH(specification_digest) = 64),
    CONSTRAINT ck_developer_scenario_runs_status CHECK
        (lifecycle_status IN ('CREATED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_developer_scenario_runs_progress CHECK
        (total_steps BETWEEN 1 AND 10 AND next_step_index BETWEEN 0 AND total_steps),
    CONSTRAINT ck_developer_scenario_runs_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lifecycle_status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT ck_developer_scenario_runs_terminal CHECK (
        (lifecycle_status IN ('CREATED', 'RUNNING') AND failure_code IS NULL
            AND report_json IS NULL AND report_digest IS NULL AND completed_at IS NULL)
        OR (lifecycle_status = 'SUCCEEDED' AND next_step_index = total_steps
            AND failure_code IS NULL AND report_json IS NOT NULL
            AND report_digest IS NOT NULL AND completed_at IS NOT NULL
            AND lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lifecycle_status = 'FAILED' AND failure_code IS NOT NULL
            AND report_json IS NULL AND report_digest IS NULL AND completed_at IS NOT NULL
            AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_developer_scenario_runs_time CHECK
        (created_at <= updated_at AND (completed_at IS NULL OR created_at <= completed_at))
);

CREATE TABLE developer_scenario_steps (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    scenario_run_id UUID NOT NULL,
    step_index INTEGER NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    fixture_id VARCHAR(32),
    amount BIGINT,
    expected_outcome_code VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_developer_scenario_steps PRIMARY KEY (id),
    CONSTRAINT uk_developer_scenario_steps_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_developer_scenario_steps_index UNIQUE (merchant_id, scenario_run_id, step_index),
    CONSTRAINT uk_developer_scenario_steps_key UNIQUE (merchant_id, scenario_run_id, step_key),
    CONSTRAINT fk_developer_scenario_steps_run FOREIGN KEY (merchant_id, scenario_run_id)
        REFERENCES developer_scenario_runs (merchant_id, id),
    CONSTRAINT ck_developer_scenario_steps_index CHECK (step_index BETWEEN 0 AND 9),
    CONSTRAINT ck_developer_scenario_steps_operation CHECK
        (operation IN ('CREATE_CUSTOMER', 'CREATE_PAYMENT_METHOD',
                       'CREATE_PAYMENT_INTENT', 'CONFIRM_PAYMENT_INTENT')),
    CONSTRAINT ck_developer_scenario_steps_resource_type CHECK (
        (operation = 'CREATE_CUSTOMER' AND resource_type = 'CUSTOMER')
        OR (operation = 'CREATE_PAYMENT_METHOD' AND resource_type = 'PAYMENT_METHOD')
        OR (operation = 'CREATE_PAYMENT_INTENT' AND resource_type = 'PAYMENT_INTENT')
        OR (operation = 'CONFIRM_PAYMENT_INTENT' AND resource_type = 'PAYMENT_ATTEMPT')),
    CONSTRAINT ck_developer_scenario_steps_fixture CHECK (
        (operation IN ('CREATE_CUSTOMER') AND fixture_id IS NULL
            AND amount IS NULL AND expected_outcome_code IS NULL)
        OR (operation = 'CREATE_PAYMENT_METHOD' AND fixture_id IS NOT NULL
            AND amount IS NULL AND expected_outcome_code IS NULL)
        OR (operation = 'CREATE_PAYMENT_INTENT' AND fixture_id IS NOT NULL
            AND amount BETWEEN 1 AND 100000000 AND expected_outcome_code IS NULL)
        OR (operation = 'CONFIRM_PAYMENT_INTENT' AND fixture_id IS NOT NULL
            AND amount BETWEEN 1 AND 100000000 AND expected_outcome_code IS NOT NULL)),
    CONSTRAINT ck_developer_scenario_steps_status CHECK
        (lifecycle_status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_developer_scenario_steps_completion CHECK (
        (lifecycle_status = 'PENDING' AND resource_id IS NULL AND completed_at IS NULL)
        OR (lifecycle_status = 'COMPLETED' AND resource_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE TABLE developer_scenario_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    scenario_run_id UUID NOT NULL,
    event_sequence INTEGER NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    step_index INTEGER,
    lease_epoch BIGINT NOT NULL,
    outcome_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_developer_scenario_events PRIMARY KEY (id),
    CONSTRAINT uk_developer_scenario_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_developer_scenario_events_sequence UNIQUE
        (merchant_id, scenario_run_id, event_sequence),
    CONSTRAINT fk_developer_scenario_events_run FOREIGN KEY (merchant_id, scenario_run_id)
        REFERENCES developer_scenario_runs (merchant_id, id),
    CONSTRAINT ck_developer_scenario_events_type CHECK
        (event_type IN ('RUN_CREATED', 'LEASE_CLAIMED', 'STEP_COMPLETED',
                        'RUN_SUCCEEDED', 'RUN_FAILED')),
    CONSTRAINT ck_developer_scenario_events_step CHECK (
        (event_type = 'STEP_COMPLETED' AND step_index BETWEEN 0 AND 9)
        OR (event_type <> 'STEP_COMPLETED' AND step_index IS NULL)),
    CONSTRAINT ck_developer_scenario_events_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_developer_scenario_events_epoch CHECK (lease_epoch >= 0)
);

CREATE INDEX idx_developer_scenario_runs_queue
    ON developer_scenario_runs (merchant_id, lifecycle_status, updated_at, id);
CREATE INDEX idx_developer_scenario_runs_expired_lease
    ON developer_scenario_runs (lifecycle_status, lease_expires_at, id);
CREATE INDEX idx_developer_scenario_steps_progress
    ON developer_scenario_steps (merchant_id, scenario_run_id, step_index);
CREATE INDEX idx_developer_scenario_events_history
    ON developer_scenario_events (merchant_id, scenario_run_id, event_sequence);
