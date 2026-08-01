ALTER TABLE payment_risk_projections
    ADD COLUMN projection_definition_id UUID;

UPDATE payment_risk_projections
SET projection_definition_id = CAST(
    '00000000-0000-0000-0000-000000000901' AS UUID)
WHERE projection_definition_id IS NULL;

ALTER TABLE payment_risk_projections
    ALTER COLUMN projection_definition_id SET NOT NULL;

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projection_definition CHECK (
        projection_definition_id = CAST(
            '00000000-0000-0000-0000-000000000901' AS UUID)
    );

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT fk_payment_risk_projection_definition_generation
    FOREIGN KEY (projection_definition_id, projection_generation_id)
    REFERENCES projection_generations (projection_definition_id, id);

ALTER TABLE merchant_risk_aggregates
    ADD COLUMN projection_definition_id UUID;

UPDATE merchant_risk_aggregates
SET projection_definition_id = CAST(
    '00000000-0000-0000-0000-000000000902' AS UUID)
WHERE projection_definition_id IS NULL;

ALTER TABLE merchant_risk_aggregates
    ALTER COLUMN projection_definition_id SET NOT NULL;

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT ck_merchant_risk_aggregate_definition CHECK (
        projection_definition_id = CAST(
            '00000000-0000-0000-0000-000000000902' AS UUID)
    );

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT fk_merchant_risk_aggregate_definition_generation
    FOREIGN KEY (projection_definition_id, projection_generation_id)
    REFERENCES projection_generations (projection_definition_id, id);

CREATE TABLE merchant_payment_cohort_states (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    projection_definition_id UUID NOT NULL,
    projection_generation_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    traffic_origin VARCHAR(32),
    intent_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    label_value VARCHAR(32),
    label_available_at TIMESTAMP(6) WITH TIME ZONE,
    last_stream_version BIGINT NOT NULL,
    last_domain_event_id UUID NOT NULL,
    last_event_record_sha256 VARCHAR(64) NOT NULL,
    last_event_recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_payment_cohort_states PRIMARY KEY (id),
    CONSTRAINT uk_merchant_payment_cohort_state UNIQUE
        (merchant_id, projection_generation_id, payment_intent_id),
    CONSTRAINT fk_merchant_payment_cohort_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_merchant_payment_cohort_definition_generation
        FOREIGN KEY (projection_definition_id, projection_generation_id)
        REFERENCES projection_generations (projection_definition_id, id),
    CONSTRAINT fk_merchant_payment_cohort_payment
        FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_merchant_payment_cohort_event
        FOREIGN KEY (merchant_id, last_domain_event_id)
        REFERENCES domain_events (merchant_id, id),
    CONSTRAINT ck_merchant_payment_cohort_definition CHECK (
        projection_definition_id = CAST(
            '00000000-0000-0000-0000-000000000902' AS UUID)
    ),
    CONSTRAINT ck_merchant_payment_cohort_origin CHECK (
        traffic_origin IS NULL
        OR traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')
    ),
    CONSTRAINT ck_merchant_payment_cohort_version CHECK (last_stream_version > 0),
    CONSTRAINT ck_merchant_payment_cohort_digest CHECK (
        LENGTH(last_event_record_sha256) = 64
    ),
    CONSTRAINT ck_merchant_payment_cohort_time CHECK (
        intent_occurred_at <= last_event_recorded_at
        AND last_processed_at >= last_event_recorded_at
        AND updated_at >= created_at
    )
);

CREATE INDEX idx_merchant_payment_cohort_labels
    ON merchant_payment_cohort_states
        (merchant_id, projection_generation_id, label_available_at,
         payment_intent_id);
