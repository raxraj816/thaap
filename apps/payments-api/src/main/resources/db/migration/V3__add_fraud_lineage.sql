ALTER TABLE fraud_decisions ADD payment_attempt_id UUID;
ALTER TABLE fraud_decisions ADD risk_score NUMERIC(5, 4);
ALTER TABLE fraud_decisions ADD risk_level VARCHAR(32);
ALTER TABLE fraud_decisions ADD model_version VARCHAR(255);
ALTER TABLE fraud_decisions ADD ruleset_version VARCHAR(255);
ALTER TABLE fraud_decisions ADD latency_ms BIGINT;
ALTER TABLE fraud_decisions ADD decision_started_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE fraud_decisions ADD decision_completed_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE fraud_decisions ADD feature_freshness_status VARCHAR(32);
ALTER TABLE fraud_decisions ADD max_feature_age_ms BIGINT;
ALTER TABLE fraud_decisions ADD reason_codes TEXT NOT NULL DEFAULT '[]';

UPDATE fraud_decisions
SET risk_score = 0.0000,
    risk_level = 'LOW',
    ruleset_version = 'legacy_placeholder_v1',
    latency_ms = 0,
    decision_started_at = created_at,
    decision_completed_at = created_at,
    feature_freshness_status = 'MISSING',
    max_feature_age_ms = 0,
    reason_codes = '["RULES_NOT_ENABLED"]'
WHERE risk_score IS NULL
   OR risk_level IS NULL
   OR ruleset_version IS NULL
   OR latency_ms IS NULL
   OR decision_started_at IS NULL
   OR decision_completed_at IS NULL
   OR feature_freshness_status IS NULL
   OR max_feature_age_ms IS NULL;

ALTER TABLE fraud_decisions ALTER COLUMN risk_score SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN risk_level SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN ruleset_version SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN latency_ms SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN decision_started_at SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN decision_completed_at SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN feature_freshness_status SET NOT NULL;
ALTER TABLE fraud_decisions ALTER COLUMN max_feature_age_ms SET NOT NULL;

ALTER TABLE fraud_decisions ADD CONSTRAINT uk_fraud_decisions_merchant_id_id UNIQUE (merchant_id, id);
ALTER TABLE fraud_decisions ADD CONSTRAINT uk_fraud_decisions_attempt UNIQUE (merchant_id, payment_attempt_id);
ALTER TABLE fraud_decisions ADD CONSTRAINT fk_fraud_decisions_attempt
    FOREIGN KEY (merchant_id, payment_attempt_id) REFERENCES payment_attempts (merchant_id, id);
ALTER TABLE fraud_decisions ADD CONSTRAINT ck_fraud_decisions_risk_score
    CHECK (risk_score >= 0 AND risk_score <= 1);
ALTER TABLE fraud_decisions ADD CONSTRAINT ck_fraud_decisions_latency_ms CHECK (latency_ms >= 0);
ALTER TABLE fraud_decisions ADD CONSTRAINT ck_fraud_decisions_max_feature_age_ms CHECK (max_feature_age_ms >= 0);

CREATE TABLE feature_snapshots (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    features_json TEXT NOT NULL,
    computed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    oldest_feature_timestamp TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    max_feature_age_ms BIGINT NOT NULL,
    freshness_status VARCHAR(32) NOT NULL,
    stale_feature_names TEXT NOT NULL DEFAULT '[]',
    missing_feature_names TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_snapshots PRIMARY KEY (id),
    CONSTRAINT uk_feature_snapshots_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_feature_snapshots_decision UNIQUE (merchant_id, fraud_decision_id),
    CONSTRAINT uk_feature_snapshots_attempt UNIQUE (merchant_id, payment_attempt_id),
    CONSTRAINT fk_feature_snapshots_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_feature_snapshots_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT fk_feature_snapshots_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id)
);

CREATE INDEX idx_fraud_decisions_attempt ON fraud_decisions (payment_attempt_id);
CREATE INDEX idx_fraud_decisions_merchant_attempt ON fraud_decisions (merchant_id, payment_attempt_id);
CREATE INDEX idx_feature_snapshots_merchant ON feature_snapshots (merchant_id);
CREATE INDEX idx_feature_snapshots_decision ON feature_snapshots (fraud_decision_id);
CREATE INDEX idx_feature_snapshots_attempt ON feature_snapshots (payment_attempt_id);
CREATE INDEX idx_feature_snapshots_merchant_created ON feature_snapshots (merchant_id, created_at, id);
ALTER TABLE feature_snapshots ADD CONSTRAINT ck_feature_snapshots_max_feature_age_ms CHECK (max_feature_age_ms >= 0);
