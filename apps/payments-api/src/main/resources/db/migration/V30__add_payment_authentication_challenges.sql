CREATE TABLE authentication_challenges (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    authorization_id UUID,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    client_secret_prefix VARCHAR(24) NOT NULL,
    client_secret_sha256 VARCHAR(64) NOT NULL,
    client_secret_identity_hmac VARCHAR(64) NOT NULL,
    client_secret_key_version VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completion_attempted_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    result_code VARCHAR(32),
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_authentication_challenges PRIMARY KEY (id),
    CONSTRAINT uk_authentication_challenges_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_authentication_challenges_attempt UNIQUE (merchant_id, payment_attempt_id),
    CONSTRAINT uk_authentication_challenges_secret_sha256 UNIQUE (client_secret_sha256),
    CONSTRAINT uk_authentication_challenges_secret_hmac UNIQUE (client_secret_identity_hmac),
    CONSTRAINT fk_authentication_challenges_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_authentication_challenges_attempt FOREIGN KEY
        (merchant_id, payment_attempt_id, payment_intent_id)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id),
    CONSTRAINT fk_authentication_challenges_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT fk_authentication_challenges_authorization FOREIGN KEY
        (merchant_id, authorization_id, payment_attempt_id)
        REFERENCES authorizations (merchant_id, id, payment_attempt_id),
    CONSTRAINT ck_authentication_challenges_source CHECK (source IN ('FRAUD_ENGINE', 'PROCESSOR')),
    CONSTRAINT ck_authentication_challenges_status CHECK (
        status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELED')
    ),
    CONSTRAINT ck_authentication_challenges_secret CHECK (
        client_secret_prefix LIKE 'authc\_%' ESCAPE '\'
        AND LENGTH(client_secret_sha256) = 64
        AND LENGTH(client_secret_identity_hmac) = 64
        AND client_secret_key_version ~ '^[A-Za-z0-9._-]{1,32}$'
    ),
    CONSTRAINT ck_authentication_challenges_source_lineage CHECK (
        (source = 'FRAUD_ENGINE') OR (source = 'PROCESSOR' AND authorization_id IS NOT NULL)
    ),
    CONSTRAINT ck_authentication_challenges_time CHECK (expires_at > created_at),
    CONSTRAINT ck_authentication_challenges_terminal CHECK (
        (status = 'PENDING' AND completion_attempted_at IS NULL
            AND completed_at IS NULL AND result_code IS NULL)
        OR (status = 'SUCCEEDED' AND completion_attempted_at IS NOT NULL
            AND completed_at IS NOT NULL AND result_code = 'AUTHENTICATED')
        OR (status = 'FAILED' AND completion_attempted_at IS NOT NULL
            AND completed_at IS NOT NULL AND result_code = 'AUTHENTICATION_FAILED')
        OR (status = 'EXPIRED' AND completion_attempted_at IS NOT NULL
            AND completed_at IS NOT NULL AND result_code = 'CHALLENGE_EXPIRED')
        OR (status = 'CANCELED' AND completion_attempted_at IS NULL
            AND completed_at IS NOT NULL AND result_code = 'PAYMENT_CANCELED')
    ),
    CONSTRAINT ck_authentication_challenges_completion_time CHECK (
        completion_attempted_at IS NULL OR completion_attempted_at >= created_at
    ),
    CONSTRAINT ck_authentication_challenges_completed_time CHECK (
        completed_at IS NULL OR completed_at >= created_at
    ),
    CONSTRAINT ck_authentication_challenges_origin CHECK (
        traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')
    )
);

CREATE INDEX idx_authentication_challenges_merchant_status
    ON authentication_challenges (merchant_id, status, expires_at, id);
CREATE INDEX idx_authentication_challenges_intent
    ON authentication_challenges (merchant_id, payment_intent_id);

ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_event_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_event_type CHECK (event_type IN (
    'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
    'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
    'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
    'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
    'authentication_challenge.created', 'authentication_challenge.completed'
));
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_resource_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_resource_type CHECK (
    resource_type IN ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'MANUAL_REVIEW_CASE', 'REFUND',
        'FRAUD_LABEL', 'AUTHENTICATION_CHALLENGE')
);

ALTER TABLE webhook_endpoint_event_subscriptions
    DROP CONSTRAINT ck_webhook_endpoint_event_subscriptions_type;
ALTER TABLE webhook_endpoint_event_subscriptions
    ADD CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (event_type IN (
        'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
        'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
        'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
        'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
        'authentication_challenge.created', 'authentication_challenge.completed'
    ));
