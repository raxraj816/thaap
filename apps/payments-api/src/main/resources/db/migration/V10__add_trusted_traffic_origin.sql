ALTER TABLE payment_intents
    ADD COLUMN traffic_origin VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_API';

ALTER TABLE payment_attempts
    ADD COLUMN traffic_origin VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_API';

ALTER TABLE fraud_decisions
    ADD COLUMN traffic_origin VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_API';

ALTER TABLE feature_snapshots
    ADD COLUMN traffic_origin VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_API';

ALTER TABLE webhook_events
    ADD COLUMN traffic_origin VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_API';

CREATE INDEX idx_payment_intents_merchant_origin
    ON payment_intents (merchant_id, traffic_origin, created_at, id);

CREATE INDEX idx_payment_attempts_merchant_origin
    ON payment_attempts (merchant_id, traffic_origin, created_at, id);

CREATE INDEX idx_fraud_decisions_merchant_origin
    ON fraud_decisions (merchant_id, traffic_origin, created_at, id);
