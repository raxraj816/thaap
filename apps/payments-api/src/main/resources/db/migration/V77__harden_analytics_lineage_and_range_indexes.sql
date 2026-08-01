ALTER TABLE payment_risk_projections
    ADD COLUMN intent_occurred_at TIMESTAMP(6) WITH TIME ZONE;

UPDATE payment_risk_projections projection
SET intent_occurred_at = (
    SELECT event.occurred_at
    FROM domain_events event
    WHERE event.merchant_id = projection.merchant_id
      AND event.stream_type = 'PAYMENT'
      AND event.stream_id = projection.payment_intent_id
      AND event.stream_version = 1
)
WHERE intent_occurred_at IS NULL;

ALTER TABLE payment_risk_projections
    ALTER COLUMN intent_occurred_at SET NOT NULL;

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projections_intent_time CHECK (
        intent_occurred_at <= last_event_occurred_at
        AND intent_occurred_at <= last_event_recorded_at
    );

CREATE INDEX idx_merchant_risk_aggregates_range
    ON merchant_risk_aggregates
        (merchant_id, projection_generation_id, interval_type,
         traffic_origin_policy, interval_start, currency);

CREATE INDEX idx_payment_risk_projections_label_availability
    ON payment_risk_projections
        (merchant_id, projection_generation_id, label_available_at,
         payment_intent_id);
