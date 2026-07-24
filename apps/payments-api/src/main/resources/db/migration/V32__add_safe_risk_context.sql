ALTER TABLE payment_attempts ADD CONSTRAINT uk_payment_attempts_risk_context_lineage
    UNIQUE (merchant_id, id, payment_intent_id, traffic_origin);
ALTER TABLE fraud_decisions ADD CONSTRAINT uk_fraud_decisions_risk_context_lineage
    UNIQUE (merchant_id, id, payment_attempt_id, traffic_origin);

CREATE TABLE risk_contexts (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID,
    ip_identity_hmac VARCHAR(64),
    user_agent_identity_hmac VARCHAR(64),
    device_identity_hmac VARCHAR(64),
    session_identity_hmac VARCHAR(64),
    identity_key_version VARCHAR(32),
    ip_country VARCHAR(2),
    shipping_country VARCHAR(2),
    checkout_channel VARCHAR(16),
    customer_account_age_days INTEGER,
    login_age_minutes INTEGER,
    bot_score_bps INTEGER,
    captcha_outcome VARCHAR(16),
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_contexts PRIMARY KEY (id),
    CONSTRAINT uk_risk_contexts_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_contexts_attempt UNIQUE (merchant_id, payment_attempt_id),
    CONSTRAINT fk_risk_contexts_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_risk_contexts_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_risk_contexts_attempt FOREIGN KEY (merchant_id, payment_attempt_id, payment_intent_id, traffic_origin)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id, traffic_origin),
    CONSTRAINT fk_risk_contexts_decision FOREIGN KEY (merchant_id, fraud_decision_id, payment_attempt_id, traffic_origin)
        REFERENCES fraud_decisions (merchant_id, id, payment_attempt_id, traffic_origin),
    CONSTRAINT ck_risk_contexts_identity_shape CHECK (
        (identity_key_version IS NULL AND ip_identity_hmac IS NULL AND user_agent_identity_hmac IS NULL
            AND device_identity_hmac IS NULL AND session_identity_hmac IS NULL)
        OR (identity_key_version ~ '^[A-Za-z0-9._-]{1,32}$' AND
            (ip_identity_hmac ~ '^[0-9a-f]{64}$' OR ip_identity_hmac IS NULL) AND
            (user_agent_identity_hmac ~ '^[0-9a-f]{64}$' OR user_agent_identity_hmac IS NULL) AND
            (device_identity_hmac ~ '^[0-9a-f]{64}$' OR device_identity_hmac IS NULL) AND
            (session_identity_hmac ~ '^[0-9a-f]{64}$' OR session_identity_hmac IS NULL))
    ),
    CONSTRAINT ck_risk_contexts_country CHECK (
        (ip_country IS NULL OR ip_country ~ '^[A-Z]{2}$') AND
        (shipping_country IS NULL OR shipping_country ~ '^[A-Z]{2}$')),
    CONSTRAINT ck_risk_contexts_channel CHECK (checkout_channel IS NULL OR checkout_channel IN
        ('WEB', 'MOBILE_WEB', 'IOS', 'ANDROID', 'API')),
    CONSTRAINT ck_risk_contexts_ranges CHECK (
        (customer_account_age_days IS NULL OR customer_account_age_days BETWEEN 0 AND 36500) AND
        (login_age_minutes IS NULL OR login_age_minutes BETWEEN 0 AND 525600) AND
        (bot_score_bps IS NULL OR bot_score_bps BETWEEN 0 AND 10000)),
    CONSTRAINT ck_risk_contexts_captcha CHECK (captcha_outcome IS NULL OR captcha_outcome IN
        ('NOT_PRESENT', 'PASSED', 'FAILED', 'UNAVAILABLE')),
    CONSTRAINT ck_risk_contexts_time CHECK (observed_at <= created_at + INTERVAL '5' MINUTE)
);

CREATE INDEX idx_risk_contexts_merchant_created
    ON risk_contexts (merchant_id, created_at, id);
