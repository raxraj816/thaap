ALTER TABLE fraud_rules RENAME TO legacy_fraud_rules;

CREATE TABLE fraud_rules (
    id UUID NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    ruleset_version VARCHAR(32) NOT NULL,
    priority INTEGER NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    condition_type VARCHAR(32) NOT NULL,
    feature_code VARCHAR(128) NOT NULL,
    currency_feature_code VARCHAR(128),
    currency_value VARCHAR(3),
    long_threshold BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_rules_v1 PRIMARY KEY (id),
    CONSTRAINT uk_fraud_rules_code_version UNIQUE (rule_code, ruleset_version),
    CONSTRAINT uk_fraud_rules_lineage UNIQUE (id, rule_code, ruleset_version),
    CONSTRAINT ck_fraud_rules_priority CHECK (priority > 0),
    CONSTRAINT ck_fraud_rules_action CHECK (action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')),
    CONSTRAINT ck_fraud_rules_reason CHECK
        (reason_code IN ('EXTREME_AMOUNT', 'REPEATED_PAYMENT_ATTEMPT', 'ELEVATED_AMOUNT')),
    CONSTRAINT ck_fraud_rules_condition CHECK (condition_type IN ('LONG_GTE', 'CURRENCY_AMOUNT_GTE')),
    CONSTRAINT ck_fraud_rules_status CHECK (status IN ('ACTIVE')),
    CONSTRAINT ck_fraud_rules_shape CHECK (
        (condition_type = 'LONG_GTE' AND currency_feature_code IS NULL AND currency_value IS NULL)
        OR (condition_type = 'CURRENCY_AMOUNT_GTE' AND currency_feature_code IS NOT NULL
            AND currency_value IS NOT NULL)
    )
);

CREATE INDEX idx_fraud_rules_ruleset_status
    ON fraud_rules (ruleset_version, status, priority, rule_code);

INSERT INTO fraud_rules
    (id, rule_code, ruleset_version, priority, action, reason_code, condition_type,
     feature_code, currency_feature_code, currency_value, long_threshold, status, created_at)
VALUES
    ('23000000-0000-0000-0000-000000000001', 'BLOCK_USD_EXTREME_AMOUNT_V1', 'rules_v1', 100,
     'BLOCK', 'EXTREME_AMOUNT', 'CURRENCY_AMOUNT_GTE', 'payment.amount_minor',
     'payment.currency', 'USD', 5000000, 'ACTIVE', CURRENT_TIMESTAMP),
    ('23000000-0000-0000-0000-000000000002', 'REVIEW_REPEATED_ATTEMPT_V1', 'rules_v1', 200,
     'REVIEW', 'REPEATED_PAYMENT_ATTEMPT', 'LONG_GTE', 'payment.attempt_number',
     NULL, NULL, 3, 'ACTIVE', CURRENT_TIMESTAMP),
    ('23000000-0000-0000-0000-000000000003', 'REQUEST_3DS_USD_ELEVATED_AMOUNT_V1', 'rules_v1', 300,
     'REQUEST_3DS', 'ELEVATED_AMOUNT', 'CURRENCY_AMOUNT_GTE', 'payment.amount_minor',
     'payment.currency', 'USD', 100000, 'ACTIVE', CURRENT_TIMESTAMP);

CREATE TABLE rule_execution_results (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    priority INTEGER NOT NULL,
    matched BOOLEAN NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    evaluation_outcome VARCHAR(16) NOT NULL,
    latency_us BIGINT NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_rule_execution_results PRIMARY KEY (id),
    CONSTRAINT uk_rule_results_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_rule_results_decision_rule UNIQUE
        (merchant_id, fraud_decision_id, rule_code, rule_version),
    CONSTRAINT fk_rule_results_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_rule_results_decision_origin FOREIGN KEY (merchant_id, fraud_decision_id, traffic_origin)
        REFERENCES fraud_decisions (merchant_id, id, traffic_origin),
    CONSTRAINT fk_rule_results_rule FOREIGN KEY (rule_id, rule_code, rule_version)
        REFERENCES fraud_rules (id, rule_code, ruleset_version),
    CONSTRAINT ck_rule_results_priority CHECK (priority > 0),
    CONSTRAINT ck_rule_results_action CHECK (action IN ('BLOCK', 'REVIEW', 'REQUEST_3DS')),
    CONSTRAINT ck_rule_results_outcome CHECK (
        (matched = TRUE AND evaluation_outcome = 'MATCH')
        OR (matched = FALSE AND evaluation_outcome = 'NO_MATCH')
    ),
    CONSTRAINT ck_rule_results_latency CHECK (latency_us >= 0)
);

CREATE INDEX idx_rule_results_merchant_decision
    ON rule_execution_results (merchant_id, fraud_decision_id, priority, rule_code);

CREATE TABLE manual_review_cases (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason_codes TEXT NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    cancellation_reason VARCHAR(32),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    canceled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_manual_review_cases PRIMARY KEY (id),
    CONSTRAINT uk_manual_reviews_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_manual_reviews_attempt UNIQUE (merchant_id, payment_attempt_id),
    CONSTRAINT uk_manual_reviews_decision UNIQUE (merchant_id, fraud_decision_id),
    CONSTRAINT fk_manual_reviews_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_manual_reviews_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_manual_reviews_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT fk_manual_reviews_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT ck_manual_reviews_status CHECK (status IN ('OPEN', 'CANCELED')),
    CONSTRAINT ck_manual_reviews_cancellation CHECK (
        (status = 'OPEN' AND cancellation_reason IS NULL AND canceled_at IS NULL)
        OR (status = 'CANCELED' AND cancellation_reason = 'MERCHANT_CANCELED' AND canceled_at IS NOT NULL)
    )
);

CREATE INDEX idx_manual_reviews_merchant_status_created
    ON manual_review_cases (merchant_id, status, created_at, id);

ALTER TABLE webhook_endpoint_event_subscriptions
    DROP CONSTRAINT ck_webhook_endpoint_event_subscriptions_type;
ALTER TABLE webhook_endpoint_event_subscriptions
    ADD CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (
        event_type IN (
            'payment_intent.created',
            'payment_attempt.created',
            'payment_intent.captured',
            'payment_intent.canceled',
            'payment_intent.blocked',
            'payment_intent.requires_action',
            'fraud_review.opened',
            'fraud_review.canceled',
            'refund.created'
        )
    );

INSERT INTO webhook_endpoint_event_subscriptions
    (merchant_id, webhook_endpoint_id, event_type, created_at)
SELECT endpoint.merchant_id, endpoint.id, new_type.event_type, CURRENT_TIMESTAMP
FROM webhook_endpoints endpoint
CROSS JOIN (
    SELECT 'payment_intent.blocked' AS event_type
    UNION ALL SELECT 'payment_intent.requires_action'
    UNION ALL SELECT 'fraud_review.opened'
    UNION ALL SELECT 'fraud_review.canceled'
) new_type
WHERE NOT EXISTS (
    SELECT 1
    FROM webhook_endpoint_event_subscriptions existing
    WHERE existing.merchant_id = endpoint.merchant_id
      AND existing.webhook_endpoint_id = endpoint.id
      AND existing.event_type = new_type.event_type
);
