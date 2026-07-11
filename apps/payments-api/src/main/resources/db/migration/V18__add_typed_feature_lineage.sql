CREATE TABLE feature_definitions (
    id UUID NOT NULL,
    feature_code VARCHAR(128) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    owner VARCHAR(64) NOT NULL,
    feature_version VARCHAR(32) NOT NULL,
    description VARCHAR(500) NOT NULL,
    max_age_ms BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_definitions PRIMARY KEY (id),
    CONSTRAINT uk_feature_definitions_code_version UNIQUE (feature_code, feature_version),
    CONSTRAINT uk_feature_definitions_lineage UNIQUE (id, feature_code, feature_version, value_type),
    CONSTRAINT ck_feature_definitions_code CHECK (feature_code ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    CONSTRAINT ck_feature_definitions_entity CHECK (entity_type IN ('PAYMENT_ATTEMPT')),
    CONSTRAINT ck_feature_definitions_type CHECK (value_type IN ('LONG', 'STRING', 'BOOLEAN', 'DECIMAL')),
    CONSTRAINT ck_feature_definitions_max_age CHECK (max_age_ms IS NULL OR max_age_ms > 0)
);

ALTER TABLE payment_attempts ADD CONSTRAINT uk_payment_attempts_feature_origin
    UNIQUE (merchant_id, id, traffic_origin);
ALTER TABLE fraud_decisions ADD CONSTRAINT uk_fraud_decisions_feature_origin
    UNIQUE (merchant_id, id, traffic_origin);
ALTER TABLE fraud_decisions ADD CONSTRAINT fk_fraud_decisions_attempt_origin
    FOREIGN KEY (merchant_id, payment_attempt_id, traffic_origin)
    REFERENCES payment_attempts (merchant_id, id, traffic_origin);
ALTER TABLE feature_snapshots ADD CONSTRAINT fk_feature_snapshots_decision_origin
    FOREIGN KEY (merchant_id, fraud_decision_id, traffic_origin)
    REFERENCES fraud_decisions (merchant_id, id, traffic_origin);
ALTER TABLE feature_snapshots ADD CONSTRAINT fk_feature_snapshots_attempt_origin
    FOREIGN KEY (merchant_id, payment_attempt_id, traffic_origin)
    REFERENCES payment_attempts (merchant_id, id, traffic_origin);

CREATE TABLE feature_values (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    feature_definition_id UUID NOT NULL,
    feature_code VARCHAR(128) NOT NULL,
    feature_version VARCHAR(32) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    long_value BIGINT,
    string_value VARCHAR(256),
    boolean_value BOOLEAN,
    decimal_value NUMERIC(19, 6),
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    computed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_values PRIMARY KEY (id),
    CONSTRAINT uk_feature_values_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_feature_values_attempt_feature UNIQUE
        (merchant_id, payment_attempt_id, feature_code, feature_version),
    CONSTRAINT fk_feature_values_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_feature_values_attempt_origin FOREIGN KEY (merchant_id, payment_attempt_id, traffic_origin)
        REFERENCES payment_attempts (merchant_id, id, traffic_origin),
    CONSTRAINT fk_feature_values_definition FOREIGN KEY
        (feature_definition_id, feature_code, feature_version, value_type)
        REFERENCES feature_definitions (id, feature_code, feature_version, value_type),
    CONSTRAINT ck_feature_values_type CHECK (value_type IN ('LONG', 'STRING', 'BOOLEAN', 'DECIMAL')),
    CONSTRAINT ck_feature_values_typed_value CHECK (
        (value_type = 'LONG' AND long_value IS NOT NULL AND string_value IS NULL
            AND boolean_value IS NULL AND decimal_value IS NULL)
        OR (value_type = 'STRING' AND long_value IS NULL AND string_value IS NOT NULL
            AND boolean_value IS NULL AND decimal_value IS NULL)
        OR (value_type = 'BOOLEAN' AND long_value IS NULL AND string_value IS NULL
            AND boolean_value IS NOT NULL AND decimal_value IS NULL)
        OR (value_type = 'DECIMAL' AND long_value IS NULL AND string_value IS NULL
            AND boolean_value IS NULL AND decimal_value IS NOT NULL)
    ),
    CONSTRAINT ck_feature_values_time_order CHECK (observed_at <= computed_at)
);

CREATE INDEX idx_feature_values_merchant_attempt
    ON feature_values (merchant_id, payment_attempt_id);
CREATE INDEX idx_feature_values_point_in_time
    ON feature_values (merchant_id, feature_code, feature_version, observed_at, payment_attempt_id);

INSERT INTO feature_definitions
    (id, feature_code, entity_type, value_type, owner, feature_version, description, max_age_ms, created_at)
VALUES
    ('22000000-0000-0000-0000-000000000001', 'payment.amount_minor', 'PAYMENT_ATTEMPT', 'LONG',
        'PAYMENTS_CORE', 'v1', 'Locked payment amount in minor currency units', NULL, CURRENT_TIMESTAMP),
    ('22000000-0000-0000-0000-000000000002', 'payment.currency', 'PAYMENT_ATTEMPT', 'STRING',
        'PAYMENTS_CORE', 'v1', 'Locked ISO 4217 payment currency', NULL, CURRENT_TIMESTAMP),
    ('22000000-0000-0000-0000-000000000003', 'payment.capture_method', 'PAYMENT_ATTEMPT', 'STRING',
        'PAYMENTS_CORE', 'v1', 'Locked capture method', NULL, CURRENT_TIMESTAMP),
    ('22000000-0000-0000-0000-000000000004', 'payment.attempt_number', 'PAYMENT_ATTEMPT', 'LONG',
        'PAYMENTS_CORE', 'v1', 'Serialized attempt number for the locked payment', NULL, CURRENT_TIMESTAMP),
    ('22000000-0000-0000-0000-000000000005', 'card.country', 'PAYMENT_ATTEMPT', 'STRING',
        'FRAUD_RISK', 'v1', 'Country code from the owned tokenized payment method', NULL, CURRENT_TIMESTAMP),
    ('22000000-0000-0000-0000-000000000006', 'card.funding', 'PAYMENT_ATTEMPT', 'STRING',
        'FRAUD_RISK', 'v1', 'Funding type from the owned tokenized payment method', NULL, CURRENT_TIMESTAMP),
    ('22000000-0000-0000-0000-000000000007', 'traffic.origin', 'PAYMENT_ATTEMPT', 'STRING',
        'PLATFORM_SECURITY', 'v1', 'Server-assigned traffic origin', NULL, CURRENT_TIMESTAMP);
