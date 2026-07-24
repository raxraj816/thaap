CREATE TABLE velocity_observations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID,
    dimension VARCHAR(32) NOT NULL,
    window_ms BIGINT NOT NULL,
    availability VARCHAR(16) NOT NULL,
    other_attempt_count BIGINT,
    redis_time_ms BIGINT,
    observed_at TIMESTAMP(6) WITH TIME ZONE,
    script_version VARCHAR(32) NOT NULL,
    execution_mode VARCHAR(16),
    failure_cause VARCHAR(32),
    identity_key_version VARCHAR(32) NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_velocity_observations PRIMARY KEY (id),
    CONSTRAINT uk_velocity_observations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_velocity_observations_attempt_dimension
        UNIQUE (merchant_id, payment_attempt_id, dimension),
    CONSTRAINT fk_velocity_observations_attempt
        FOREIGN KEY (merchant_id, payment_attempt_id, payment_intent_id, traffic_origin)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id, traffic_origin),
    CONSTRAINT fk_velocity_observations_decision
        FOREIGN KEY (merchant_id, fraud_decision_id, payment_attempt_id, traffic_origin)
        REFERENCES fraud_decisions (merchant_id, id, payment_attempt_id, traffic_origin),
    CONSTRAINT ck_velocity_observations_dimension CHECK (dimension IN (
        'CARD_ATTEMPTS_10M', 'IP_ATTEMPTS_10M', 'CUSTOMER_ATTEMPTS_1H', 'MERCHANT_ATTEMPTS_1M')),
    CONSTRAINT ck_velocity_observations_window CHECK (window_ms BETWEEN 1000 AND 86400000),
    CONSTRAINT ck_velocity_observations_availability CHECK (availability IN ('AVAILABLE', 'UNAVAILABLE')),
    CONSTRAINT ck_velocity_observations_count CHECK (other_attempt_count IS NULL OR other_attempt_count >= 0),
    CONSTRAINT ck_velocity_observations_redis_time CHECK (redis_time_ms IS NULL OR redis_time_ms >= 0),
    CONSTRAINT ck_velocity_observations_script CHECK (script_version = 'velocity_v1'),
    CONSTRAINT ck_velocity_observations_mode CHECK (execution_mode IS NULL OR execution_mode IN ('FIRST', 'RETRY')),
    CONSTRAINT ck_velocity_observations_key_version CHECK (
        CHAR_LENGTH(identity_key_version) BETWEEN 1 AND 32),
    CONSTRAINT ck_velocity_observations_shape CHECK (
        (availability = 'AVAILABLE'
            AND other_attempt_count IS NOT NULL
            AND redis_time_ms IS NOT NULL
            AND observed_at IS NOT NULL
            AND execution_mode IS NOT NULL
            AND failure_cause IS NULL)
        OR
        (availability = 'UNAVAILABLE'
            AND other_attempt_count IS NULL
            AND redis_time_ms IS NULL
            AND observed_at IS NULL
            AND execution_mode IS NULL
            AND failure_cause IS NOT NULL))
);

CREATE INDEX idx_velocity_observations_merchant_attempt
    ON velocity_observations (merchant_id, payment_attempt_id, dimension);
CREATE INDEX idx_velocity_observations_decision
    ON velocity_observations (merchant_id, fraud_decision_id, dimension);
