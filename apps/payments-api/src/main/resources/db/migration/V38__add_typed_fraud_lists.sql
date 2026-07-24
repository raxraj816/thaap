ALTER TABLE customers ADD COLUMN email_domain_hash VARCHAR(64);

CREATE TABLE fraud_lists (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    list_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    identity_type VARCHAR(32) NOT NULL,
    generic_namespace VARCHAR(32),
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_lists PRIMARY KEY (id),
    CONSTRAINT uk_fraud_lists_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_fraud_lists_identity UNIQUE (merchant_id, id, identity_type),
    CONSTRAINT uk_fraud_lists_merchant_code UNIQUE (merchant_id, list_code),
    CONSTRAINT fk_fraud_lists_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_fraud_lists_creator FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_fraud_lists_identity_type CHECK (identity_type IN (
        'CARD_FINGERPRINT', 'BIN', 'COUNTRY', 'IP', 'CUSTOMER', 'EMAIL',
        'EMAIL_DOMAIN', 'GENERIC_STRING')),
    CONSTRAINT ck_fraud_lists_namespace CHECK (
        (identity_type = 'GENERIC_STRING' AND generic_namespace IN ('DEVICE_ID', 'SESSION_ID'))
        OR (identity_type <> 'GENERIC_STRING' AND generic_namespace IS NULL))
);

CREATE TABLE fraud_list_versions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    fraud_list_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    effective_from TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_list_versions PRIMARY KEY (id),
    CONSTRAINT uk_fraud_list_versions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_fraud_list_versions_list_identity UNIQUE (merchant_id, id, fraud_list_id),
    CONSTRAINT uk_fraud_list_versions_list_version UNIQUE
        (merchant_id, fraud_list_id, version_number),
    CONSTRAINT fk_fraud_list_versions_list FOREIGN KEY (merchant_id, fraud_list_id)
        REFERENCES fraud_lists (merchant_id, id),
    CONSTRAINT fk_fraud_list_versions_creator FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_fraud_list_versions_version CHECK (version_number > 0)
);

CREATE TABLE fraud_list_entries (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    fraud_list_id UUID NOT NULL,
    fraud_list_version_id UUID NOT NULL,
    identity_type VARCHAR(32) NOT NULL,
    canonical_value VARCHAR(128),
    value_hmac VARCHAR(64),
    hmac_key_version VARCHAR(32),
    action VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    effective_from TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    effective_until TIMESTAMP(6) WITH TIME ZONE,
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_list_entries PRIMARY KEY (id),
    CONSTRAINT uk_fraud_list_entries_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_fraud_list_entries_version_identity UNIQUE
        (merchant_id, id, fraud_list_version_id, fraud_list_id),
    CONSTRAINT uk_fraud_list_entries_canonical UNIQUE
        (merchant_id, fraud_list_version_id, canonical_value),
    CONSTRAINT uk_fraud_list_entries_hmac UNIQUE
        (merchant_id, fraud_list_version_id, value_hmac, hmac_key_version),
    CONSTRAINT fk_fraud_list_entries_list FOREIGN KEY (merchant_id, fraud_list_id, identity_type)
        REFERENCES fraud_lists (merchant_id, id, identity_type),
    CONSTRAINT fk_fraud_list_entries_version FOREIGN KEY
        (merchant_id, fraud_list_version_id, fraud_list_id)
        REFERENCES fraud_list_versions (merchant_id, id, fraud_list_id),
    CONSTRAINT fk_fraud_list_entries_creator FOREIGN KEY (merchant_id, created_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_fraud_list_entries_identity_type CHECK (identity_type IN (
        'CARD_FINGERPRINT', 'BIN', 'COUNTRY', 'IP', 'CUSTOMER', 'EMAIL',
        'EMAIL_DOMAIN', 'GENERIC_STRING')),
    CONSTRAINT ck_fraud_list_entries_value_shape CHECK (
        (identity_type IN ('BIN', 'COUNTRY')
            AND canonical_value IS NOT NULL AND value_hmac IS NULL AND hmac_key_version IS NULL)
        OR (identity_type NOT IN ('BIN', 'COUNTRY')
            AND canonical_value IS NULL AND CHAR_LENGTH(value_hmac) = 64
            AND CHAR_LENGTH(hmac_key_version) BETWEEN 1 AND 32)),
    CONSTRAINT ck_fraud_list_entries_action CHECK (action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')),
    CONSTRAINT ck_fraud_list_entries_reason CHECK (reason_code IN (
        'KNOWN_FRAUD', 'CHARGEBACK_HISTORY', 'ACCOUNT_ABUSE', 'BOT_ACTIVITY',
        'MANUAL_RISK_CONTROL')),
    CONSTRAINT ck_fraud_list_entries_interval CHECK (
        effective_until IS NULL OR effective_until > effective_from)
);

CREATE TABLE fraud_list_lifecycle_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    fraud_list_id UUID NOT NULL,
    fraud_list_version_id UUID NOT NULL,
    fraud_list_entry_id UUID,
    lifecycle_target_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_operator_id UUID NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_list_lifecycle_events PRIMARY KEY (id),
    CONSTRAINT uk_fraud_list_lifecycle_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_fraud_list_lifecycle_event_once UNIQUE
        (merchant_id, lifecycle_target_id, event_type),
    CONSTRAINT fk_fraud_list_lifecycle_list FOREIGN KEY (merchant_id, fraud_list_id)
        REFERENCES fraud_lists (merchant_id, id),
    CONSTRAINT fk_fraud_list_lifecycle_version FOREIGN KEY
        (merchant_id, fraud_list_version_id, fraud_list_id)
        REFERENCES fraud_list_versions (merchant_id, id, fraud_list_id),
    CONSTRAINT fk_fraud_list_lifecycle_entry FOREIGN KEY
        (merchant_id, fraud_list_entry_id, fraud_list_version_id, fraud_list_id)
        REFERENCES fraud_list_entries (merchant_id, id, fraud_list_version_id, fraud_list_id),
    CONSTRAINT fk_fraud_list_lifecycle_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_fraud_list_lifecycle_type CHECK (event_type IN (
        'VERSION_ACTIVATED', 'VERSION_DEACTIVATED', 'ENTRY_DEACTIVATED')),
    CONSTRAINT ck_fraud_list_lifecycle_shape CHECK (
        (event_type IN ('VERSION_ACTIVATED', 'VERSION_DEACTIVATED') AND fraud_list_entry_id IS NULL)
        OR (event_type = 'ENTRY_DEACTIVATED' AND fraud_list_entry_id IS NOT NULL))
);

CREATE TABLE fraud_list_evaluations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    fraud_list_id UUID NOT NULL,
    fraud_list_version_id UUID NOT NULL,
    matched_entry_id UUID,
    identity_type VARCHAR(32) NOT NULL,
    matched BOOLEAN NOT NULL,
    candidate_action VARCHAR(16),
    reason_code VARCHAR(64),
    evaluated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_list_evaluations PRIMARY KEY (id),
    CONSTRAINT uk_fraud_list_evaluations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_fraud_list_evaluations_attempt_list UNIQUE
        (merchant_id, payment_attempt_id, fraud_list_version_id),
    CONSTRAINT fk_fraud_list_evaluations_attempt FOREIGN KEY
        (merchant_id, payment_attempt_id, traffic_origin)
        REFERENCES payment_attempts (merchant_id, id, traffic_origin),
    CONSTRAINT fk_fraud_list_evaluations_decision FOREIGN KEY
        (merchant_id, fraud_decision_id, payment_attempt_id, traffic_origin)
        REFERENCES fraud_decisions (merchant_id, id, payment_attempt_id, traffic_origin),
    CONSTRAINT fk_fraud_list_evaluations_list FOREIGN KEY (merchant_id, fraud_list_id)
        REFERENCES fraud_lists (merchant_id, id),
    CONSTRAINT fk_fraud_list_evaluations_version FOREIGN KEY
        (merchant_id, fraud_list_version_id, fraud_list_id)
        REFERENCES fraud_list_versions (merchant_id, id, fraud_list_id),
    CONSTRAINT fk_fraud_list_evaluations_entry FOREIGN KEY
        (merchant_id, matched_entry_id, fraud_list_version_id, fraud_list_id)
        REFERENCES fraud_list_entries (merchant_id, id, fraud_list_version_id, fraud_list_id),
    CONSTRAINT ck_fraud_list_evaluations_identity_type CHECK (identity_type IN (
        'CARD_FINGERPRINT', 'BIN', 'COUNTRY', 'IP', 'CUSTOMER', 'EMAIL',
        'EMAIL_DOMAIN', 'GENERIC_STRING')),
    CONSTRAINT ck_fraud_list_evaluations_action CHECK (
        candidate_action IS NULL OR candidate_action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')),
    CONSTRAINT ck_fraud_list_evaluations_reason CHECK (reason_code IS NULL OR reason_code IN (
        'KNOWN_FRAUD', 'CHARGEBACK_HISTORY', 'ACCOUNT_ABUSE', 'BOT_ACTIVITY',
        'MANUAL_RISK_CONTROL')),
    CONSTRAINT ck_fraud_list_evaluations_shape CHECK (
        (matched = TRUE AND matched_entry_id IS NOT NULL
            AND candidate_action IS NOT NULL AND reason_code IS NOT NULL)
        OR (matched = FALSE AND matched_entry_id IS NULL
            AND candidate_action IS NULL AND reason_code IS NULL))
);

CREATE INDEX idx_fraud_list_entries_active
    ON fraud_list_entries (merchant_id, fraud_list_version_id, effective_from, effective_until);
CREATE INDEX idx_fraud_list_lifecycle_lookup
    ON fraud_list_lifecycle_events (merchant_id, fraud_list_version_id, fraud_list_entry_id, occurred_at);
CREATE INDEX idx_fraud_list_evaluations_decision
    ON fraud_list_evaluations (merchant_id, fraud_decision_id, fraud_list_version_id);
