CREATE TABLE merchant_risk_aggregates (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    projection_generation_id UUID NOT NULL,
    interval_type VARCHAR(8) NOT NULL,
    interval_start TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin_policy VARCHAR(24) NOT NULL,
    payment_count BIGINT NOT NULL,
    attempt_count BIGINT NOT NULL,
    authorization_count BIGINT NOT NULL,
    decline_count BIGINT NOT NULL,
    review_count BIGINT NOT NULL,
    confirmed_fraud_count BIGINT NOT NULL,
    chargeback_count BIGINT NOT NULL,
    enumeration_signal_count BIGINT NOT NULL,
    unlabeled_count BIGINT NOT NULL,
    unknown_outcome_count BIGINT NOT NULL,
    intent_amount_minor BIGINT NOT NULL,
    authorized_amount_minor BIGINT NOT NULL,
    captured_amount_minor BIGINT NOT NULL,
    refunded_amount_minor BIGINT NOT NULL,
    disputed_amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source_event_count BIGINT NOT NULL,
    latest_domain_event_id UUID NOT NULL,
    latest_source_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    latest_source_recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    projection_version VARCHAR(32) NOT NULL,
    evaluator_version VARCHAR(32) NOT NULL,
    coverage_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_risk_aggregates PRIMARY KEY (id),
    CONSTRAINT uk_merchant_risk_aggregates_interval UNIQUE
        (merchant_id, projection_generation_id, interval_type, interval_start,
         traffic_origin_policy, currency),
    CONSTRAINT fk_merchant_risk_aggregates_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_merchant_risk_aggregates_generation FOREIGN KEY (projection_generation_id)
        REFERENCES projection_generations (id),
    CONSTRAINT fk_merchant_risk_aggregates_event
        FOREIGN KEY (merchant_id, latest_domain_event_id)
        REFERENCES domain_events (merchant_id, id),
    CONSTRAINT ck_merchant_risk_aggregates_interval CHECK
        (interval_type IN ('HOUR', 'DAY')),
    CONSTRAINT ck_merchant_risk_aggregates_origin CHECK
        (traffic_origin_policy IN ('ALL', 'LIVE_ONLY', 'SYNTHETIC_ONLY')),
    CONSTRAINT ck_merchant_risk_aggregates_counts CHECK (
        payment_count >= 0 AND attempt_count >= 0
        AND authorization_count >= 0 AND decline_count >= 0
        AND review_count >= 0 AND confirmed_fraud_count >= 0
        AND chargeback_count >= 0 AND enumeration_signal_count >= 0
        AND unlabeled_count >= 0 AND unknown_outcome_count >= 0
        AND source_event_count > 0),
    CONSTRAINT ck_merchant_risk_aggregates_amounts CHECK (
        intent_amount_minor >= 0 AND authorized_amount_minor >= 0
        AND captured_amount_minor >= 0 AND refunded_amount_minor >= 0
        AND disputed_amount_minor >= 0),
    CONSTRAINT ck_merchant_risk_aggregates_currency CHECK (LENGTH(currency) = 3),
    CONSTRAINT ck_merchant_risk_aggregates_coverage CHECK
        (coverage_status IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_merchant_risk_aggregates_time CHECK (
        latest_source_recorded_at >= latest_source_occurred_at
        AND last_processed_at >= latest_source_recorded_at
        AND updated_at >= created_at)
);

CREATE INDEX idx_merchant_risk_aggregates_timeseries
    ON merchant_risk_aggregates
        (merchant_id, projection_generation_id, interval_type,
         traffic_origin_policy, currency, interval_start DESC);

CREATE INDEX idx_merchant_risk_aggregates_freshness
    ON merchant_risk_aggregates
        (merchant_id, projection_generation_id, last_processed_at DESC, id);
