CREATE TABLE merchant_risk_configuration_guards (
    merchant_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_configuration_guards PRIMARY KEY (merchant_id),
    CONSTRAINT fk_merchant_risk_configuration_guard_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id)
);

INSERT INTO merchant_risk_configuration_guards (merchant_id, created_at)
SELECT id, created_at FROM merchants;

CREATE TABLE merchant_risk_profile_versions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    risk_tier VARCHAR(16) NOT NULL,
    source VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_profile_versions PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_profile_versions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_merchant_risk_profile_versions_number UNIQUE (merchant_id, version_number),
    CONSTRAINT fk_merchant_risk_profile_versions_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_merchant_risk_profile_versions_creator FOREIGN KEY
        (merchant_id, created_by_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_merchant_risk_profile_version_number CHECK (version_number > 0),
    CONSTRAINT ck_merchant_risk_profile_tier CHECK (risk_tier IN ('STANDARD', 'ELEVATED', 'HIGH')),
    CONSTRAINT ck_merchant_risk_profile_source CHECK (source = 'RISK_MANAGER'),
    CONSTRAINT ck_merchant_risk_profile_reason CHECK (reason_code IN (
        'MANUAL_RISK_REVIEW', 'MERCHANT_RISK_CHANGE', 'FRAUD_EXPOSURE_CHANGE'))
);

CREATE TABLE merchant_risk_profile_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    profile_version_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_operator_id UUID NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_profile_events PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_profile_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_merchant_risk_profile_event_once UNIQUE
        (merchant_id, profile_version_id, event_type),
    CONSTRAINT fk_merchant_risk_profile_events_version FOREIGN KEY
        (merchant_id, profile_version_id)
        REFERENCES merchant_risk_profile_versions (merchant_id, id),
    CONSTRAINT fk_merchant_risk_profile_events_actor FOREIGN KEY
        (merchant_id, actor_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_merchant_risk_profile_event_type CHECK (event_type IN (
        'SUBMITTED', 'APPROVED', 'ACTIVATED', 'RETIRED'))
);

CREATE TABLE merchant_risk_policy_versions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    version_number INTEGER NOT NULL,
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_policy_versions PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_policy_versions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_merchant_risk_policy_versions_code_version UNIQUE
        (merchant_id, policy_code, version_number),
    CONSTRAINT fk_merchant_risk_policy_versions_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_merchant_risk_policy_versions_creator FOREIGN KEY
        (merchant_id, created_by_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_merchant_risk_policy_version_number CHECK (version_number > 0),
    CONSTRAINT ck_merchant_risk_policy_code CHECK (
        CHAR_LENGTH(policy_code) BETWEEN 3 AND 64)
);

CREATE TABLE merchant_risk_policy_thresholds (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    threshold_code VARCHAR(64) NOT NULL,
    feature_definition_id UUID NOT NULL,
    feature_code VARCHAR(128) NOT NULL,
    feature_version VARCHAR(32) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    operator VARCHAR(8) NOT NULL,
    long_operand BIGINT,
    boolean_operand BOOLEAN,
    string_operands_json VARCHAR(2048),
    candidate_action VARCHAR(16) NOT NULL,
    priority INTEGER NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    currency_scope VARCHAR(3),
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_policy_thresholds PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_policy_thresholds_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_merchant_risk_policy_thresholds_identity UNIQUE
        (merchant_id, id, policy_version_id, feature_definition_id,
         feature_code, feature_version, value_type),
    CONSTRAINT uk_merchant_risk_policy_thresholds_code UNIQUE
        (merchant_id, policy_version_id, threshold_code),
    CONSTRAINT fk_merchant_risk_policy_thresholds_policy FOREIGN KEY
        (merchant_id, policy_version_id)
        REFERENCES merchant_risk_policy_versions (merchant_id, id),
    CONSTRAINT fk_merchant_risk_policy_thresholds_definition FOREIGN KEY
        (feature_definition_id, feature_code, feature_version, value_type)
        REFERENCES feature_definitions (id, feature_code, feature_version, value_type),
    CONSTRAINT fk_merchant_risk_policy_thresholds_creator FOREIGN KEY
        (merchant_id, created_by_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_merchant_risk_policy_threshold_operator CHECK (operator IN ('EQ', 'GTE', 'IN')),
    CONSTRAINT ck_merchant_risk_policy_threshold_type CHECK
        (value_type IN ('LONG', 'STRING', 'BOOLEAN')),
    CONSTRAINT ck_merchant_risk_policy_threshold_operand CHECK (
        (operator = 'GTE' AND value_type = 'LONG' AND long_operand IS NOT NULL
            AND boolean_operand IS NULL AND string_operands_json IS NULL)
        OR (operator = 'EQ' AND value_type = 'LONG' AND long_operand IS NOT NULL
            AND boolean_operand IS NULL AND string_operands_json IS NULL)
        OR (operator = 'EQ' AND value_type = 'BOOLEAN' AND long_operand IS NULL
            AND boolean_operand IS NOT NULL AND string_operands_json IS NULL)
        OR (operator = 'EQ' AND value_type = 'STRING' AND long_operand IS NULL
            AND boolean_operand IS NULL AND string_operands_json IS NOT NULL)
        OR (operator = 'IN' AND value_type = 'STRING' AND long_operand IS NULL
            AND boolean_operand IS NULL AND string_operands_json IS NOT NULL)),
    CONSTRAINT ck_merchant_risk_policy_threshold_action CHECK
        (candidate_action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')),
    CONSTRAINT ck_merchant_risk_policy_threshold_priority CHECK (priority BETWEEN 0 AND 1000),
    CONSTRAINT ck_merchant_risk_policy_threshold_reason CHECK (reason_code IN (
        'MERCHANT_AMOUNT_THRESHOLD', 'MERCHANT_VELOCITY_THRESHOLD',
        'MERCHANT_CONTEXT_THRESHOLD', 'MERCHANT_RISK_TIER')),
    CONSTRAINT ck_merchant_risk_policy_threshold_currency CHECK (
        currency_scope IS NULL OR currency_scope IN ('USD', 'EUR', 'GBP', 'INR'))
);

CREATE TABLE merchant_risk_policy_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_operator_id UUID NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_policy_events PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_policy_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_merchant_risk_policy_event_once UNIQUE
        (merchant_id, policy_version_id, event_type),
    CONSTRAINT fk_merchant_risk_policy_events_version FOREIGN KEY
        (merchant_id, policy_version_id)
        REFERENCES merchant_risk_policy_versions (merchant_id, id),
    CONSTRAINT fk_merchant_risk_policy_events_actor FOREIGN KEY
        (merchant_id, actor_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_merchant_risk_policy_event_type CHECK (event_type IN (
        'SUBMITTED', 'APPROVED', 'ACTIVATED', 'RETIRED'))
);

CREATE TABLE risk_control_evaluations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    control_type VARCHAR(32) NOT NULL,
    configuration_status VARCHAR(32) NOT NULL,
    profile_version_id UUID,
    policy_version_id UUID,
    policy_threshold_id UUID,
    feature_value_id UUID,
    feature_definition_id UUID NOT NULL,
    feature_code VARCHAR(128) NOT NULL,
    feature_version VARCHAR(32) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    observed_long BIGINT,
    observed_boolean BOOLEAN,
    observed_string VARCHAR(256),
    operator VARCHAR(8),
    operand_long BIGINT,
    operand_boolean BOOLEAN,
    operand_strings_json VARCHAR(2048),
    matched BOOLEAN NOT NULL,
    candidate_action VARCHAR(16),
    priority INTEGER,
    reason_code VARCHAR(64),
    currency_scope VARCHAR(3),
    evaluated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_control_evaluations PRIMARY KEY (id),
    CONSTRAINT uk_risk_control_evaluations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_control_evaluations_attempt_control UNIQUE
        (merchant_id, payment_attempt_id, control_type, feature_code, policy_threshold_id),
    CONSTRAINT fk_risk_control_evaluations_attempt FOREIGN KEY
        (merchant_id, payment_attempt_id, traffic_origin)
        REFERENCES payment_attempts (merchant_id, id, traffic_origin),
    CONSTRAINT fk_risk_control_evaluations_decision FOREIGN KEY
        (merchant_id, fraud_decision_id, payment_attempt_id, traffic_origin)
        REFERENCES fraud_decisions (merchant_id, id, payment_attempt_id, traffic_origin),
    CONSTRAINT fk_risk_control_evaluations_profile FOREIGN KEY
        (merchant_id, profile_version_id)
        REFERENCES merchant_risk_profile_versions (merchant_id, id),
    CONSTRAINT fk_risk_control_evaluations_policy FOREIGN KEY
        (merchant_id, policy_version_id)
        REFERENCES merchant_risk_policy_versions (merchant_id, id),
    CONSTRAINT fk_risk_control_evaluations_threshold FOREIGN KEY
        (merchant_id, policy_threshold_id, policy_version_id, feature_definition_id,
         feature_code, feature_version, value_type)
        REFERENCES merchant_risk_policy_thresholds
        (merchant_id, id, policy_version_id, feature_definition_id,
         feature_code, feature_version, value_type),
    CONSTRAINT fk_risk_control_evaluations_feature_value FOREIGN KEY
        (merchant_id, feature_value_id) REFERENCES feature_values (merchant_id, id),
    CONSTRAINT fk_risk_control_evaluations_definition FOREIGN KEY
        (feature_definition_id, feature_code, feature_version, value_type)
        REFERENCES feature_definitions (id, feature_code, feature_version, value_type),
    CONSTRAINT ck_risk_control_evaluations_type CHECK
        (control_type IN ('PROFILE', 'POLICY_THRESHOLD')),
    CONSTRAINT ck_risk_control_evaluations_configuration CHECK
        (configuration_status IN ('APPLICATION_DEFAULT', 'ACTIVE', 'UNAVAILABLE')),
    CONSTRAINT ck_risk_control_evaluations_observed_shape CHECK (
        (value_type = 'LONG' AND observed_boolean IS NULL AND observed_string IS NULL)
        OR (value_type = 'BOOLEAN' AND observed_long IS NULL AND observed_string IS NULL)
        OR (value_type = 'STRING' AND observed_long IS NULL AND observed_boolean IS NULL)),
    CONSTRAINT ck_risk_control_evaluations_shape CHECK (
        (control_type = 'PROFILE' AND policy_version_id IS NULL
            AND policy_threshold_id IS NULL AND operator IS NULL
            AND candidate_action IS NULL AND priority IS NULL AND reason_code IS NULL
            AND currency_scope IS NULL AND matched = TRUE
            AND feature_value_id IS NOT NULL AND value_type = 'STRING'
            AND observed_string IN ('STANDARD', 'ELEVATED', 'HIGH'))
        OR (control_type = 'POLICY_THRESHOLD' AND configuration_status = 'ACTIVE'
            AND profile_version_id IS NULL AND policy_version_id IS NOT NULL
            AND policy_threshold_id IS NOT NULL AND operator IN ('EQ', 'GTE', 'IN')
            AND candidate_action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')
            AND priority BETWEEN 0 AND 1000 AND reason_code IS NOT NULL))
);

CREATE INDEX idx_merchant_risk_profile_events_lookup
    ON merchant_risk_profile_events (merchant_id, profile_version_id, occurred_at);
CREATE INDEX idx_merchant_risk_policy_events_lookup
    ON merchant_risk_policy_events (merchant_id, policy_version_id, occurred_at);
CREATE INDEX idx_merchant_risk_policy_thresholds_policy
    ON merchant_risk_policy_thresholds (merchant_id, policy_version_id, priority, threshold_code);
CREATE INDEX idx_risk_control_evaluations_decision
    ON risk_control_evaluations (merchant_id, fraud_decision_id, control_type);
