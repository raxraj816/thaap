ALTER TABLE refunds ADD COLUMN reason_catalog_version INTEGER DEFAULT 1;
ALTER TABLE refunds ADD COLUMN reason_code VARCHAR(64) DEFAULT 'LEGACY_UNSPECIFIED';
ALTER TABLE refunds ADD COLUMN source_review_resolution_id UUID;
UPDATE refunds SET reason_catalog_version = 1, reason_code = 'LEGACY_UNSPECIFIED';
ALTER TABLE refunds ALTER COLUMN reason_catalog_version SET NOT NULL;
ALTER TABLE refunds ALTER COLUMN reason_code SET NOT NULL;
ALTER TABLE refunds ALTER COLUMN reason_catalog_version DROP DEFAULT;
ALTER TABLE refunds ALTER COLUMN reason_code DROP DEFAULT;
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_source_review_resolution FOREIGN KEY
    (merchant_id, source_review_resolution_id) REFERENCES review_resolutions (merchant_id, id);
ALTER TABLE refunds ADD CONSTRAINT uk_refunds_source_review_resolution
    UNIQUE (merchant_id, source_review_resolution_id);
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_reason_catalog CHECK (
    reason_catalog_version = 1
    AND reason_code IN ('LEGACY_UNSPECIFIED', 'MERCHANT_REQUESTED',
        'RISK_REVIEW_REMEDIATION', 'CONFIRMED_FRAUD_CUSTOMER_PROTECTION',
        'CUSTOMER_SERVICE_REMEDIATION')
    AND ((source_review_resolution_id IS NULL
            AND reason_code IN ('LEGACY_UNSPECIFIED', 'MERCHANT_REQUESTED'))
        OR (source_review_resolution_id IS NOT NULL
            AND reason_code IN ('RISK_REVIEW_REMEDIATION',
                'CONFIRMED_FRAUD_CUSTOMER_PROTECTION', 'CUSTOMER_SERVICE_REMEDIATION')))
);

CREATE TABLE post_authorization_signals (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    external_event_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    authorization_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    recommendation VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    source_attestation VARCHAR(64) NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    evidence_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_post_authorization_signals PRIMARY KEY (id),
    CONSTRAINT uk_post_auth_signals_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_post_auth_signals_external_event UNIQUE (merchant_id, external_event_key),
    CONSTRAINT fk_post_auth_signals_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_post_auth_signals_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_post_auth_signals_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT fk_post_auth_signals_authorization FOREIGN KEY (merchant_id, authorization_id)
        REFERENCES authorizations (merchant_id, id),
    CONSTRAINT fk_post_auth_signals_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT ck_post_auth_signals_recommendation CHECK (
        recommendation IN ('NO_ACTION', 'REVIEW', 'REFUND')),
    CONSTRAINT ck_post_auth_signals_reason CHECK (reason_code IN (
        'ACCOUNT_TAKEOVER_INDICATOR', 'STOLEN_CREDENTIAL_INDICATOR',
        'CARD_TESTING_INDICATOR', 'CUSTOMER_REPORTED_FRAUD', 'MERCHANT_RISK_ALERT')),
    CONSTRAINT ck_post_auth_signals_source CHECK (
        source_attestation = 'SIMULATOR_MERCHANT_INGESTED'),
    CONSTRAINT ck_post_auth_signals_origin CHECK (
        traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_post_auth_signals_time CHECK (
        evidence_occurred_at <= received_at AND received_at <= available_at
        AND available_at = created_at)
);
CREATE INDEX idx_post_auth_signals_merchant_created
    ON post_authorization_signals (merchant_id, created_at, id);

ALTER TABLE manual_review_cases ADD COLUMN source_post_authorization_signal_id UUID;
ALTER TABLE manual_review_cases DROP CONSTRAINT uk_manual_reviews_attempt;
ALTER TABLE manual_review_cases DROP CONSTRAINT uk_manual_reviews_decision;
ALTER TABLE manual_review_cases DROP CONSTRAINT ck_manual_reviews_stage;
ALTER TABLE manual_review_cases DROP CONSTRAINT ck_manual_reviews_decision_action;
ALTER TABLE manual_review_cases ADD CONSTRAINT fk_manual_reviews_post_auth_signal FOREIGN KEY
    (merchant_id, source_post_authorization_signal_id)
    REFERENCES post_authorization_signals (merchant_id, id);
ALTER TABLE manual_review_cases ADD CONSTRAINT uk_manual_reviews_attempt_stage
    UNIQUE (merchant_id, payment_attempt_id, review_stage);
ALTER TABLE manual_review_cases ADD CONSTRAINT uk_manual_reviews_signal
    UNIQUE (merchant_id, source_post_authorization_signal_id);
ALTER TABLE manual_review_cases ADD CONSTRAINT ck_manual_reviews_stage CHECK (
    (review_stage = 'PRE_AUTHORIZATION' AND source_post_authorization_signal_id IS NULL
        AND decision_action = 'REVIEW')
    OR (review_stage = 'POST_AUTHORIZATION' AND source_post_authorization_signal_id IS NOT NULL)
);

ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_stage;
ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_outcome;
ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_semantics;
ALTER TABLE review_resolutions ADD CONSTRAINT ck_review_resolutions_stage CHECK (
    review_stage IN ('PRE_AUTHORIZATION', 'POST_AUTHORIZATION'));
ALTER TABLE review_resolutions ADD CONSTRAINT ck_review_resolutions_outcome CHECK (
    outcome IN ('APPROVE', 'CANCEL', 'CANCEL_AND_REPORT_FRAUD',
        'NO_ACTION', 'REFUND', 'REFUND_AND_REPORT_FRAUD'));
ALTER TABLE review_resolutions ADD CONSTRAINT ck_review_resolutions_semantics CHECK (
    (review_stage = 'PRE_AUTHORIZATION' AND (
        (outcome = 'APPROVE' AND classification IN ('INCONCLUSIVE', 'CONFIRMED_LEGITIMATE')
            AND fraud_type IS NULL AND reason_code = 'MANUAL_REVIEW_APPROVED')
        OR (outcome = 'CANCEL' AND classification = 'INCONCLUSIVE'
            AND fraud_type IS NULL AND reason_code = 'RISK_TOO_HIGH')
        OR (outcome = 'CANCEL_AND_REPORT_FRAUD' AND classification = 'CONFIRMED_FRAUD'
            AND ((fraud_type = 'STOLEN_PAYMENT_CREDENTIAL' AND reason_code = 'IDENTITY_MISMATCH_CONFIRMED')
                OR (fraud_type = 'ACCOUNT_TAKEOVER' AND reason_code = 'ACCOUNT_TAKEOVER_CONFIRMED')
                OR (fraud_type = 'CARD_TESTING' AND reason_code = 'CARD_TESTING_CONFIRMED')
                OR (fraud_type = 'FIRST_PARTY_MISUSE' AND reason_code = 'FIRST_PARTY_MISUSE_CONFIRMED')
                OR (fraud_type = 'OTHER_CONFIRMED_FRAUD' AND reason_code = 'OTHER_FRAUD_CONFIRMED')))))
    OR (review_stage = 'POST_AUTHORIZATION' AND (
        (outcome = 'NO_ACTION' AND classification IN ('INCONCLUSIVE', 'CONFIRMED_LEGITIMATE')
            AND fraud_type IS NULL AND reason_code = 'POST_AUTHORIZATION_NO_ACTION')
        OR (outcome = 'REFUND' AND classification = 'INCONCLUSIVE'
            AND fraud_type IS NULL AND reason_code = 'POST_AUTHORIZATION_REFUND')
        OR (outcome = 'REFUND_AND_REPORT_FRAUD' AND classification = 'CONFIRMED_FRAUD'
            AND ((fraud_type = 'STOLEN_PAYMENT_CREDENTIAL' AND reason_code = 'IDENTITY_MISMATCH_CONFIRMED')
                OR (fraud_type = 'ACCOUNT_TAKEOVER' AND reason_code = 'ACCOUNT_TAKEOVER_CONFIRMED')
                OR (fraud_type = 'CARD_TESTING' AND reason_code = 'CARD_TESTING_CONFIRMED')
                OR (fraud_type = 'FIRST_PARTY_MISUSE' AND reason_code = 'FIRST_PARTY_MISUSE_CONFIRMED')
                OR (fraud_type = 'OTHER_CONFIRMED_FRAUD' AND reason_code = 'OTHER_FRAUD_CONFIRMED')))))
);

ALTER TABLE fraud_labels ADD COLUMN source_post_authorization_signal_id UUID;
ALTER TABLE fraud_labels DROP CONSTRAINT ck_fraud_labels_semantics;
ALTER TABLE fraud_labels ADD CONSTRAINT fk_fraud_labels_post_auth_signal FOREIGN KEY
    (merchant_id, source_post_authorization_signal_id)
    REFERENCES post_authorization_signals (merchant_id, id);
ALTER TABLE fraud_labels ADD CONSTRAINT uk_fraud_labels_source_post_auth_signal
    UNIQUE (merchant_id, source_post_authorization_signal_id);
ALTER TABLE fraud_labels ADD CONSTRAINT ck_fraud_labels_semantics CHECK (
    (source = 'MANUAL_REVIEW' AND source_review_resolution_id IS NOT NULL
        AND actor_operator_id IS NOT NULL AND source_chargeback_id IS NULL
        AND source_post_authorization_signal_id IS NULL)
    OR (source = 'POST_AUTHORIZATION_SIGNAL' AND source_review_resolution_id IS NOT NULL
        AND actor_operator_id IS NOT NULL AND source_chargeback_id IS NULL
        AND source_post_authorization_signal_id IS NOT NULL)
    OR (source = 'CHARGEBACK' AND source_review_resolution_id IS NULL
        AND actor_operator_id IS NULL AND source_chargeback_id IS NOT NULL
        AND source_post_authorization_signal_id IS NULL AND label_value = 'FRAUD'
        AND fraud_type IN ('STOLEN_PAYMENT_CREDENTIAL', 'ACCOUNT_TAKEOVER', 'CARD_TESTING',
            'FIRST_PARTY_MISUSE'))
);

CREATE TABLE review_refund_actions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    review_resolution_id UUID NOT NULL,
    refund_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    action_result VARCHAR(16) NOT NULL,
    reason_catalog_version INTEGER NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_refund_actions PRIMARY KEY (id),
    CONSTRAINT uk_review_refund_actions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_refund_actions_resolution UNIQUE (merchant_id, review_resolution_id),
    CONSTRAINT fk_review_refund_actions_resolution FOREIGN KEY (merchant_id, review_resolution_id)
        REFERENCES review_resolutions (merchant_id, id),
    CONSTRAINT fk_review_refund_actions_refund FOREIGN KEY (merchant_id, refund_id, payment_intent_id)
        REFERENCES refunds (merchant_id, id, payment_intent_id),
    CONSTRAINT ck_review_refund_actions_result CHECK (action_result IN ('CREATED', 'REUSED')),
    CONSTRAINT ck_review_refund_actions_reason CHECK (
        reason_catalog_version = 1 AND reason_code IN (
            'RISK_REVIEW_REMEDIATION', 'CONFIRMED_FRAUD_CUSTOMER_PROTECTION'))
);

ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_event_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_event_type CHECK (event_type IN (
    'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
    'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
    'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
    'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
    'authentication_challenge.created', 'authentication_challenge.completed',
    'chargeback.created', 'dispute_evidence_packet.created', 'post_authorization_signal.created'
));
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_resource_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_resource_type CHECK (
    resource_type IN ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'MANUAL_REVIEW_CASE', 'REFUND',
        'FRAUD_LABEL', 'AUTHENTICATION_CHALLENGE', 'CHARGEBACK', 'DISPUTE_EVIDENCE_PACKET',
        'POST_AUTHORIZATION_SIGNAL')
);
ALTER TABLE webhook_endpoint_event_subscriptions
    DROP CONSTRAINT ck_webhook_endpoint_event_subscriptions_type;
ALTER TABLE webhook_endpoint_event_subscriptions
    ADD CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (event_type IN (
        'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
        'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
        'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
        'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
        'authentication_challenge.created', 'authentication_challenge.completed',
        'chargeback.created', 'dispute_evidence_packet.created', 'post_authorization_signal.created'
    ));
