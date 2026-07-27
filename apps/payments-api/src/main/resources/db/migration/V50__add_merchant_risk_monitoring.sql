CREATE TABLE fraud_taxonomy_definitions (
    taxonomy_version VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    fraud_family VARCHAR(64) NOT NULL,
    fraud_type VARCHAR(64),
    display_label VARCHAR(128) NOT NULL,
    active_from TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_taxonomy_definitions PRIMARY KEY
        (taxonomy_version, source_type, source_code),
    CONSTRAINT ck_fraud_taxonomy_source_type CHECK (source_type IN (
        'FRAUD_TYPE', 'CHARGEBACK_REASON', 'INCIDENT_TRIGGER', 'SCENARIO_LABEL',
        'REPORTING_BUCKET')),
    CONSTRAINT ck_fraud_taxonomy_family CHECK (fraud_family IN (
        'PAYMENT_CREDENTIAL_ABUSE', 'ACCOUNT_COMPROMISE', 'FIRST_PARTY_MISUSE',
        'NON_FRAUD_DISPUTE', 'LEGITIMATE_OR_UNKNOWN', 'OTHER')),
    CONSTRAINT ck_fraud_taxonomy_fraud_type CHECK (fraud_type IS NULL OR fraud_type IN (
        'STOLEN_PAYMENT_CREDENTIAL', 'ACCOUNT_TAKEOVER', 'CARD_TESTING',
        'FIRST_PARTY_MISUSE', 'OTHER_CONFIRMED_FRAUD')),
    CONSTRAINT ck_fraud_taxonomy_code_size CHECK (
        CHAR_LENGTH(source_code) BETWEEN 1 AND 128
        AND CHAR_LENGTH(display_label) BETWEEN 1 AND 128)
);

INSERT INTO fraud_taxonomy_definitions (
    taxonomy_version, source_type, source_code, fraud_family, fraud_type,
    display_label, active_from
) VALUES
('fraud_taxonomy_v1', 'FRAUD_TYPE', 'STOLEN_PAYMENT_CREDENTIAL',
 'PAYMENT_CREDENTIAL_ABUSE', 'STOLEN_PAYMENT_CREDENTIAL', 'Stolen payment credential',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'FRAUD_TYPE', 'ACCOUNT_TAKEOVER',
 'ACCOUNT_COMPROMISE', 'ACCOUNT_TAKEOVER', 'Account takeover',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'FRAUD_TYPE', 'CARD_TESTING',
 'PAYMENT_CREDENTIAL_ABUSE', 'CARD_TESTING', 'Card testing',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'FRAUD_TYPE', 'FIRST_PARTY_MISUSE',
 'FIRST_PARTY_MISUSE', 'FIRST_PARTY_MISUSE', 'First-party misuse',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'FRAUD_TYPE', 'OTHER_CONFIRMED_FRAUD',
 'OTHER', 'OTHER_CONFIRMED_FRAUD', 'Other confirmed fraud',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'FRAUDULENT_STOLEN_CREDENTIAL',
 'PAYMENT_CREDENTIAL_ABUSE', 'STOLEN_PAYMENT_CREDENTIAL', 'Fraudulent stolen credential',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'FRAUDULENT_ACCOUNT_TAKEOVER',
 'ACCOUNT_COMPROMISE', 'ACCOUNT_TAKEOVER', 'Fraudulent account takeover',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'FRAUDULENT_CARD_TESTING',
 'PAYMENT_CREDENTIAL_ABUSE', 'CARD_TESTING', 'Fraudulent card testing',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'FRAUDULENT_FIRST_PARTY_MISUSE',
 'FIRST_PARTY_MISUSE', 'FIRST_PARTY_MISUSE', 'Fraudulent first-party misuse',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'PRODUCT_NOT_RECEIVED',
 'NON_FRAUD_DISPUTE', NULL, 'Product not received',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'DUPLICATE',
 'NON_FRAUD_DISPUTE', NULL, 'Duplicate charge',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'CREDIT_NOT_PROCESSED',
 'NON_FRAUD_DISPUTE', NULL, 'Credit not processed',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'CHARGEBACK_REASON', 'GENERAL',
 'NON_FRAUD_DISPUTE', NULL, 'General dispute',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'INCIDENT_TRIGGER', 'PERSISTED_CARD_TESTING_PATTERN',
 'PAYMENT_CREDENTIAL_ABUSE', 'CARD_TESTING', 'Persisted card-testing pattern',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'SCENARIO_LABEL', 'happy_path_approved_authorization',
 'LEGITIMATE_OR_UNKNOWN', NULL, 'Happy-path approved authorization',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'SCENARIO_LABEL', 'retryable_processor_decline',
 'LEGITIMATE_OR_UNKNOWN', NULL, 'Retryable processor decline',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'SCENARIO_LABEL', 'basic_card_testing_pattern',
 'PAYMENT_CREDENTIAL_ABUSE', 'CARD_TESTING', 'Basic card-testing pattern',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE)),
('fraud_taxonomy_v1', 'REPORTING_BUCKET', 'UNCLASSIFIED',
 'LEGITIMATE_OR_UNKNOWN', NULL, 'Unclassified',
 CAST('2026-07-25T00:00:00Z' AS TIMESTAMP(6) WITH TIME ZONE));

ALTER TABLE fraud_labels ADD COLUMN taxonomy_version VARCHAR(32);
ALTER TABLE fraud_labels ADD CONSTRAINT ck_fraud_labels_taxonomy_version CHECK (
    taxonomy_version IS NULL OR taxonomy_version = 'fraud_taxonomy_v1');

CREATE TABLE merchant_risk_metric_snapshots (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    created_by_operator_id UUID NOT NULL,
    definition_version VARCHAR(32) NOT NULL,
    window_start TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    as_of TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin_policy VARCHAR(32) NOT NULL,
    result_json TEXT NOT NULL,
    result_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_metric_snapshots PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_metric_snapshots_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_merchant_risk_metric_snapshots_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_merchant_risk_metric_snapshots_operator FOREIGN KEY
        (merchant_id, created_by_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_merchant_risk_metric_snapshot_version CHECK (
        definition_version = 'merchant_risk_metrics_v1'),
    CONSTRAINT ck_merchant_risk_metric_snapshot_window CHECK (
        window_start < window_end AND window_end <= as_of),
    CONSTRAINT ck_merchant_risk_metric_snapshot_origin CHECK (
        traffic_origin_policy IN ('MERCHANT_API_ONLY', 'INCLUDE_SYNTHETIC')),
    CONSTRAINT ck_merchant_risk_metric_snapshot_digest CHECK (
        CHAR_LENGTH(result_sha256) = 64)
);

CREATE TABLE merchant_risk_profile_recommendations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    metric_snapshot_id UUID NOT NULL,
    recommended_tier VARCHAR(16) NOT NULL,
    reason_metric_code VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_profile_recommendations PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_profile_recommendations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_merchant_risk_profile_recommendations_snapshot UNIQUE
        (merchant_id, metric_snapshot_id),
    CONSTRAINT fk_merchant_risk_profile_recommendations_snapshot FOREIGN KEY
        (merchant_id, metric_snapshot_id)
        REFERENCES merchant_risk_metric_snapshots (merchant_id, id),
    CONSTRAINT ck_merchant_risk_profile_recommendation_tier CHECK (
        recommended_tier IN ('ELEVATED', 'HIGH')),
    CONSTRAINT ck_merchant_risk_profile_recommendation_metric CHECK (
        reason_metric_code IN ('CONFIRMED_FRAUD_RATE', 'CHARGEBACK_COUNT_RATE',
            'ENUMERATION_RATIO', 'REVIEW_BACKLOG')),
    CONSTRAINT ck_merchant_risk_profile_recommendation_reason CHECK (
        reason_code IN ('FRAUD_EXPOSURE_CHANGE', 'MERCHANT_RISK_CHANGE'))
);

CREATE INDEX idx_fraud_taxonomy_definitions_family
    ON fraud_taxonomy_definitions (taxonomy_version, fraud_family, source_type, source_code);
CREATE INDEX idx_merchant_risk_metric_snapshots_lookup
    ON merchant_risk_metric_snapshots (merchant_id, created_at, id);
CREATE INDEX idx_merchant_risk_profile_recommendations_lookup
    ON merchant_risk_profile_recommendations (merchant_id, created_at, id);

CREATE INDEX idx_payment_attempts_monitoring
    ON payment_attempts (merchant_id, traffic_origin, created_at, id);
CREATE INDEX idx_fraud_labels_monitoring
    ON fraud_labels (merchant_id, traffic_origin, taxonomy_version, available_at,
        payment_attempt_id, id);
CREATE INDEX idx_chargebacks_monitoring
    ON chargebacks (merchant_id, traffic_origin, available_at, payment_intent_id, id);
CREATE INDEX idx_manual_reviews_monitoring
    ON manual_review_cases (merchant_id, traffic_origin, created_at, status, id);
