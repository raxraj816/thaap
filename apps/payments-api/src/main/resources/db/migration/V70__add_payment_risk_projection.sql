CREATE TABLE payment_risk_projections (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    projection_generation_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    intent_amount_minor BIGINT,
    currency VARCHAR(3),
    capture_method VARCHAR(32),
    traffic_origin VARCHAR(32),
    attempt_number INTEGER,
    attempt_outcome VARCHAR(48),
    processor_code VARCHAR(48),
    fraud_action VARCHAR(32),
    risk_level VARCHAR(32),
    risk_score INTEGER,
    reason_codes_json VARCHAR(2048),
    ruleset_version VARCHAR(64),
    feature_catalogue_version VARCHAR(64),
    review_status VARCHAR(32),
    review_outcome VARCHAR(48),
    captured_amount_minor BIGINT NOT NULL,
    refunded_amount_minor BIGINT NOT NULL,
    disputed_amount_minor BIGINT NOT NULL,
    label_value VARCHAR(32),
    fraud_type VARCHAR(64),
    label_source VARCHAR(48),
    label_available_at TIMESTAMP(6) WITH TIME ZONE,
    lifecycle_status VARCHAR(48) NOT NULL,
    coverage_status VARCHAR(16) NOT NULL,
    last_stream_version BIGINT NOT NULL,
    last_domain_event_id UUID NOT NULL,
    last_event_record_sha256 VARCHAR(64) NOT NULL,
    last_event_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_event_recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_risk_projections PRIMARY KEY (id),
    CONSTRAINT uk_payment_risk_projections_payment UNIQUE
        (merchant_id, projection_generation_id, payment_intent_id),
    CONSTRAINT fk_payment_risk_projections_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_payment_risk_projections_generation FOREIGN KEY (projection_generation_id)
        REFERENCES projection_generations (id),
    CONSTRAINT fk_payment_risk_projections_payment
        FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_payment_risk_projections_event
        FOREIGN KEY (merchant_id, last_domain_event_id)
        REFERENCES domain_events (merchant_id, id),
    CONSTRAINT ck_payment_risk_projections_amounts CHECK (
        (intent_amount_minor IS NULL OR intent_amount_minor >= 0)
        AND captured_amount_minor >= 0
        AND refunded_amount_minor >= 0
        AND disputed_amount_minor >= 0),
    CONSTRAINT ck_payment_risk_projections_currency CHECK
        (currency IS NULL OR LENGTH(currency) = 3),
    CONSTRAINT ck_payment_risk_projections_score CHECK
        (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_payment_risk_projections_origin CHECK
        (traffic_origin IS NULL OR traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_payment_risk_projections_coverage CHECK
        (coverage_status IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_payment_risk_projections_version CHECK (last_stream_version > 0),
    CONSTRAINT ck_payment_risk_projections_digest CHECK
        (LENGTH(last_event_record_sha256) = 64),
    CONSTRAINT ck_payment_risk_projections_time CHECK (
        last_event_recorded_at >= last_event_occurred_at
        AND last_processed_at >= last_event_recorded_at
        AND updated_at >= created_at)
);

CREATE INDEX idx_payment_risk_projections_search
    ON payment_risk_projections
        (merchant_id, projection_generation_id, last_event_recorded_at DESC,
         payment_intent_id);

CREATE INDEX idx_payment_risk_projections_outcome
    ON payment_risk_projections
        (merchant_id, projection_generation_id, fraud_action, review_status,
         label_value, last_event_recorded_at DESC, payment_intent_id);

