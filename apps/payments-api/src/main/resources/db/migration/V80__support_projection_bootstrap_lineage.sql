ALTER TABLE projection_bootstrap_facts
    ADD COLUMN payment_intent_id UUID;

ALTER TABLE projection_bootstrap_facts
    ADD COLUMN base_stream_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE projection_bootstrap_facts
    ADD COLUMN base_terminal_domain_event_id UUID;

ALTER TABLE projection_bootstrap_facts
    ADD COLUMN base_terminal_event_record_sha256 VARCHAR(64);

ALTER TABLE projection_bootstrap_facts
    ADD CONSTRAINT uk_projection_bootstrap_facts_merchant_id_id
        UNIQUE (merchant_id, id);

ALTER TABLE projection_bootstrap_facts
    ADD CONSTRAINT fk_projection_bootstrap_facts_payment_intent
        FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id);

ALTER TABLE projection_bootstrap_facts
    ADD CONSTRAINT fk_projection_bootstrap_facts_base_event
        FOREIGN KEY (merchant_id, base_terminal_domain_event_id)
        REFERENCES domain_events (merchant_id, id);

ALTER TABLE projection_bootstrap_facts
    ADD CONSTRAINT ck_projection_bootstrap_facts_payment_source CHECK (
        (source_type = 'PAYMENT_INTENT' AND payment_intent_id = source_id)
        OR (source_type <> 'PAYMENT_INTENT' AND payment_intent_id IS NULL)
    );

ALTER TABLE projection_bootstrap_facts
    ADD CONSTRAINT ck_projection_bootstrap_facts_base CHECK (
        base_stream_version >= 0
        AND ((base_stream_version = 0
                AND base_terminal_domain_event_id IS NULL
                AND base_terminal_event_record_sha256 IS NULL)
            OR (base_stream_version > 0
                AND base_terminal_domain_event_id IS NOT NULL
                AND LENGTH(base_terminal_event_record_sha256) = 64))
    );

ALTER TABLE payment_risk_projections
    ADD COLUMN source_bootstrap_fact_id UUID;

ALTER TABLE payment_risk_projections
    ALTER COLUMN last_domain_event_id DROP NOT NULL;

ALTER TABLE payment_risk_projections
    ALTER COLUMN last_event_record_sha256 DROP NOT NULL;

ALTER TABLE payment_risk_projections
    ALTER COLUMN last_event_occurred_at DROP NOT NULL;

ALTER TABLE payment_risk_projections
    ALTER COLUMN last_event_recorded_at DROP NOT NULL;

ALTER TABLE payment_risk_projections
    DROP CONSTRAINT ck_payment_risk_projections_version;

ALTER TABLE payment_risk_projections
    DROP CONSTRAINT ck_payment_risk_projections_digest;

ALTER TABLE payment_risk_projections
    DROP CONSTRAINT ck_payment_risk_projections_time;

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT fk_payment_risk_projections_bootstrap_fact
        FOREIGN KEY (merchant_id, source_bootstrap_fact_id)
        REFERENCES projection_bootstrap_facts (merchant_id, id);

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projections_source_lineage CHECK (
        source_bootstrap_fact_id IS NOT NULL OR last_stream_version > 0
    );

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projections_event_lineage CHECK (
        (last_stream_version = 0
            AND last_domain_event_id IS NULL
            AND last_event_record_sha256 IS NULL
            AND last_event_occurred_at IS NULL
            AND last_event_recorded_at IS NULL)
        OR (last_stream_version > 0
            AND last_domain_event_id IS NOT NULL
            AND LENGTH(last_event_record_sha256) = 64
            AND last_event_occurred_at IS NOT NULL
            AND last_event_recorded_at IS NOT NULL
            AND last_event_recorded_at >= last_event_occurred_at)
    );

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projections_bootstrap_time CHECK (
        last_stream_version >= 0
        AND last_processed_at >= COALESCE(last_event_recorded_at, intent_occurred_at)
        AND updated_at >= created_at
    );

ALTER TABLE merchant_payment_cohort_states
    ADD COLUMN source_bootstrap_fact_id UUID;

ALTER TABLE merchant_payment_cohort_states
    ALTER COLUMN last_domain_event_id DROP NOT NULL;

ALTER TABLE merchant_payment_cohort_states
    ALTER COLUMN last_event_record_sha256 DROP NOT NULL;

ALTER TABLE merchant_payment_cohort_states
    ALTER COLUMN last_event_recorded_at DROP NOT NULL;

ALTER TABLE merchant_payment_cohort_states
    DROP CONSTRAINT ck_merchant_payment_cohort_version;

ALTER TABLE merchant_payment_cohort_states
    DROP CONSTRAINT ck_merchant_payment_cohort_digest;

ALTER TABLE merchant_payment_cohort_states
    DROP CONSTRAINT ck_merchant_payment_cohort_time;

ALTER TABLE merchant_payment_cohort_states
    ADD CONSTRAINT fk_merchant_payment_cohort_bootstrap_fact
        FOREIGN KEY (merchant_id, source_bootstrap_fact_id)
        REFERENCES projection_bootstrap_facts (merchant_id, id);

ALTER TABLE merchant_payment_cohort_states
    ADD CONSTRAINT ck_merchant_payment_cohort_source_lineage CHECK (
        source_bootstrap_fact_id IS NOT NULL OR last_stream_version > 0
    );

ALTER TABLE merchant_payment_cohort_states
    ADD CONSTRAINT ck_merchant_payment_cohort_event_lineage CHECK (
        (last_stream_version = 0
            AND last_domain_event_id IS NULL
            AND last_event_record_sha256 IS NULL
            AND last_event_recorded_at IS NULL)
        OR (last_stream_version > 0
            AND last_domain_event_id IS NOT NULL
            AND LENGTH(last_event_record_sha256) = 64
            AND last_event_recorded_at IS NOT NULL)
    );

ALTER TABLE merchant_payment_cohort_states
    ADD CONSTRAINT ck_merchant_payment_cohort_bootstrap_time CHECK (
        last_stream_version >= 0
        AND intent_occurred_at <= COALESCE(last_event_recorded_at, last_processed_at)
        AND last_processed_at >= COALESCE(last_event_recorded_at, intent_occurred_at)
        AND updated_at >= created_at
    );

ALTER TABLE merchant_risk_aggregates
    ADD COLUMN source_bootstrap_fact_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE merchant_risk_aggregates
    ADD COLUMN latest_bootstrap_fact_id UUID;

ALTER TABLE merchant_risk_aggregates
    ALTER COLUMN latest_domain_event_id DROP NOT NULL;

ALTER TABLE merchant_risk_aggregates
    ALTER COLUMN latest_source_occurred_at DROP NOT NULL;

ALTER TABLE merchant_risk_aggregates
    ALTER COLUMN latest_source_recorded_at DROP NOT NULL;

ALTER TABLE merchant_risk_aggregates
    DROP CONSTRAINT ck_merchant_risk_aggregates_counts;

ALTER TABLE merchant_risk_aggregates
    DROP CONSTRAINT ck_merchant_risk_aggregates_time;

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT fk_merchant_risk_aggregates_bootstrap_fact
        FOREIGN KEY (merchant_id, latest_bootstrap_fact_id)
        REFERENCES projection_bootstrap_facts (merchant_id, id);

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT ck_merchant_risk_aggregates_counts CHECK (
        payment_count >= 0 AND attempt_count >= 0
        AND authorization_count >= 0 AND decline_count >= 0
        AND review_count >= 0 AND confirmed_fraud_count >= 0
        AND chargeback_count >= 0 AND enumeration_signal_count >= 0
        AND unlabeled_count >= 0 AND unknown_outcome_count >= 0
        AND source_event_count >= 0 AND source_bootstrap_fact_count >= 0
        AND source_event_count + source_bootstrap_fact_count > 0
    );

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT ck_merchant_risk_aggregates_source_lineage CHECK (
        (source_event_count = 0
            AND latest_domain_event_id IS NULL
            AND latest_source_occurred_at IS NULL
            AND latest_source_recorded_at IS NULL)
        OR (source_event_count > 0
            AND latest_domain_event_id IS NOT NULL
            AND latest_source_occurred_at IS NOT NULL
            AND latest_source_recorded_at IS NOT NULL
            AND latest_source_recorded_at >= latest_source_occurred_at)
    );

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT ck_merchant_risk_aggregates_bootstrap_lineage CHECK (
        (source_bootstrap_fact_count = 0 AND latest_bootstrap_fact_id IS NULL)
        OR (source_bootstrap_fact_count > 0 AND latest_bootstrap_fact_id IS NOT NULL)
    );

ALTER TABLE merchant_risk_aggregates
    ADD CONSTRAINT ck_merchant_risk_aggregates_time CHECK (
        last_processed_at >= COALESCE(latest_source_recorded_at, interval_start)
        AND updated_at >= created_at
    );

CREATE INDEX idx_projection_bootstrap_facts_payment_base
    ON projection_bootstrap_facts
        (bootstrap_manifest_id, merchant_id, payment_intent_id,
         base_stream_version);
