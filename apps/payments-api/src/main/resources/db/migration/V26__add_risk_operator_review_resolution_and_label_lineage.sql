CREATE TABLE risk_operators (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    operator_code VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    lifecycle_epoch BIGINT NOT NULL,
    created_by_api_key_prefix VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_risk_operators PRIMARY KEY (id),
    CONSTRAINT uk_risk_operators_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_operators_merchant_code UNIQUE (merchant_id, operator_code),
    CONSTRAINT fk_risk_operators_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT ck_risk_operators_role CHECK (role IN ('VIEWER', 'ANALYST', 'RISK_MANAGER')),
    CONSTRAINT ck_risk_operators_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_risk_operators_lifecycle CHECK (
        (status = 'ACTIVE' AND lifecycle_epoch = 1 AND disabled_at IS NULL)
        OR (status = 'DISABLED' AND lifecycle_epoch = 2 AND disabled_at IS NOT NULL)
    )
);

CREATE TABLE risk_operator_credentials (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    risk_operator_id UUID NOT NULL,
    secret_prefix VARCHAR(32) NOT NULL,
    secret_sha256 VARCHAR(64) NOT NULL,
    secret_identity_hmac VARCHAR(64) NOT NULL,
    secret_hmac_key_version VARCHAR(32) NOT NULL,
    creation_identity_hmac VARCHAR(64) NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_hash_key_version VARCHAR(32) NOT NULL,
    replay_ciphertext TEXT,
    replay_nonce VARCHAR(64),
    replay_key_version VARCHAR(32),
    replay_expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_risk_operator_credentials PRIMARY KEY (id),
    CONSTRAINT uk_risk_operator_credentials_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_risk_operator_credentials_operator UNIQUE (merchant_id, risk_operator_id),
    CONSTRAINT uk_risk_operator_credentials_sha256 UNIQUE (secret_sha256),
    CONSTRAINT uk_risk_operator_credentials_hmac UNIQUE (secret_identity_hmac),
    CONSTRAINT uk_risk_operator_credentials_creation UNIQUE (merchant_id, creation_identity_hmac),
    CONSTRAINT fk_risk_operator_credentials_operator FOREIGN KEY (merchant_id, risk_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_risk_operator_credentials_secret_hash CHECK (
        LENGTH(secret_sha256) = 64 AND LENGTH(secret_identity_hmac) = 64
    ),
    CONSTRAINT ck_risk_operator_credentials_creation_hash CHECK (
        LENGTH(creation_identity_hmac) = 64 AND LENGTH(creation_request_hash) = 64
    ),
    CONSTRAINT ck_risk_operator_credentials_replay CHECK (
        (replay_ciphertext IS NULL AND replay_nonce IS NULL AND replay_key_version IS NULL
            AND replay_expires_at IS NULL)
        OR (replay_ciphertext IS NOT NULL AND replay_nonce IS NOT NULL
            AND replay_key_version IS NOT NULL AND replay_expires_at IS NOT NULL)
    )
);

CREATE INDEX idx_risk_operator_credentials_auth
    ON risk_operator_credentials (secret_identity_hmac, disabled_at);

ALTER TABLE manual_review_cases
    ADD COLUMN review_stage VARCHAR(32) DEFAULT 'PRE_AUTHORIZATION';
ALTER TABLE manual_review_cases ADD COLUMN assigned_operator_id UUID;
ALTER TABLE manual_review_cases ADD COLUMN assignment_version BIGINT DEFAULT 0;
ALTER TABLE manual_review_cases ADD COLUMN resolved_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE manual_review_cases ALTER COLUMN review_stage SET NOT NULL;
ALTER TABLE manual_review_cases ALTER COLUMN assignment_version SET NOT NULL;
ALTER TABLE manual_review_cases ALTER COLUMN review_stage DROP DEFAULT;
ALTER TABLE manual_review_cases ALTER COLUMN assignment_version DROP DEFAULT;
ALTER TABLE manual_review_cases
    ADD CONSTRAINT fk_manual_reviews_assigned_operator FOREIGN KEY (merchant_id, assigned_operator_id)
        REFERENCES risk_operators (merchant_id, id);
ALTER TABLE manual_review_cases
    ADD CONSTRAINT ck_manual_reviews_stage CHECK (review_stage = 'PRE_AUTHORIZATION');
ALTER TABLE manual_review_cases
    ADD CONSTRAINT ck_manual_reviews_assignment_version CHECK (assignment_version >= 0);
ALTER TABLE manual_review_cases DROP CONSTRAINT ck_manual_reviews_status;
ALTER TABLE manual_review_cases
    ADD CONSTRAINT ck_manual_reviews_status CHECK (status IN ('OPEN', 'ASSIGNED', 'RESOLVED', 'CANCELED'));
ALTER TABLE manual_review_cases DROP CONSTRAINT ck_manual_reviews_cancellation;
ALTER TABLE manual_review_cases
    ADD CONSTRAINT ck_manual_reviews_cancellation CHECK (
        (status = 'OPEN' AND assigned_operator_id IS NULL AND cancellation_reason IS NULL
            AND canceled_at IS NULL AND resolved_at IS NULL)
        OR (status = 'ASSIGNED' AND assigned_operator_id IS NOT NULL AND cancellation_reason IS NULL
            AND canceled_at IS NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND assigned_operator_id IS NULL AND cancellation_reason IS NULL
            AND canceled_at IS NULL AND resolved_at IS NOT NULL)
        OR (status = 'CANCELED' AND assigned_operator_id IS NULL
            AND cancellation_reason = 'MERCHANT_CANCELED' AND canceled_at IS NOT NULL
            AND resolved_at IS NULL)
    );

CREATE TABLE review_assignment_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    manual_review_case_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    from_operator_id UUID,
    to_operator_id UUID,
    actor_operator_id UUID NOT NULL,
    assignment_version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_assignment_events PRIMARY KEY (id),
    CONSTRAINT uk_review_assignment_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_assignment_events_case_version UNIQUE
        (merchant_id, manual_review_case_id, assignment_version),
    CONSTRAINT fk_review_assignment_events_case FOREIGN KEY (merchant_id, manual_review_case_id)
        REFERENCES manual_review_cases (merchant_id, id),
    CONSTRAINT fk_review_assignment_events_from FOREIGN KEY (merchant_id, from_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_review_assignment_events_to FOREIGN KEY (merchant_id, to_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_review_assignment_events_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_assignment_events_type CHECK (event_type IN ('ASSIGNED', 'RELEASED', 'REASSIGNED')),
    CONSTRAINT ck_review_assignment_events_shape CHECK (
        (event_type = 'ASSIGNED' AND from_operator_id IS NULL AND to_operator_id IS NOT NULL)
        OR (event_type = 'RELEASED' AND from_operator_id IS NOT NULL AND to_operator_id IS NULL)
        OR (event_type = 'REASSIGNED' AND from_operator_id IS NOT NULL AND to_operator_id IS NOT NULL
            AND from_operator_id <> to_operator_id)
    ),
    CONSTRAINT ck_review_assignment_events_version CHECK (assignment_version > 0)
);

CREATE TABLE review_annotations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    manual_review_case_id UUID NOT NULL,
    actor_operator_id UUID NOT NULL,
    code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_annotations PRIMARY KEY (id),
    CONSTRAINT uk_review_annotations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_review_annotations_case FOREIGN KEY (merchant_id, manual_review_case_id)
        REFERENCES manual_review_cases (merchant_id, id),
    CONSTRAINT fk_review_annotations_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_annotations_code CHECK (code IN (
        'PAYMENT_PATTERN_CONFIRMED', 'CUSTOMER_CONTACTED', 'IDENTITY_MISMATCH',
        'DEVICE_BEHAVIOR_SUSPICIOUS', 'NO_ADDITIONAL_EVIDENCE'
    ))
);

CREATE TABLE review_resolutions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    manual_review_case_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    actor_operator_id UUID NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    review_stage VARCHAR(32) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    fraud_type VARCHAR(64),
    reason_code VARCHAR(64) NOT NULL,
    resolved_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_resolutions PRIMARY KEY (id),
    CONSTRAINT uk_review_resolutions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_resolutions_case UNIQUE (merchant_id, manual_review_case_id),
    CONSTRAINT fk_review_resolutions_case FOREIGN KEY (merchant_id, manual_review_case_id)
        REFERENCES manual_review_cases (merchant_id, id),
    CONSTRAINT fk_review_resolutions_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_review_resolutions_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT fk_review_resolutions_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT fk_review_resolutions_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_resolutions_role CHECK (actor_role IN ('ANALYST', 'RISK_MANAGER')),
    CONSTRAINT ck_review_resolutions_stage CHECK (review_stage = 'PRE_AUTHORIZATION'),
    CONSTRAINT ck_review_resolutions_outcome CHECK (outcome IN ('CANCEL', 'CANCEL_AND_REPORT_FRAUD')),
    CONSTRAINT ck_review_resolutions_classification CHECK (
        (outcome = 'CANCEL' AND classification = 'INCONCLUSIVE' AND fraud_type IS NULL)
        OR (outcome = 'CANCEL_AND_REPORT_FRAUD' AND classification = 'CONFIRMED_FRAUD'
            AND fraud_type IN ('STOLEN_PAYMENT_CREDENTIAL', 'ACCOUNT_TAKEOVER', 'CARD_TESTING',
                'FIRST_PARTY_MISUSE', 'OTHER_CONFIRMED_FRAUD'))
    ),
    CONSTRAINT ck_review_resolutions_reason CHECK (reason_code IN (
        'RISK_TOO_HIGH', 'IDENTITY_MISMATCH_CONFIRMED', 'CARD_TESTING_CONFIRMED',
        'ACCOUNT_TAKEOVER_CONFIRMED', 'FIRST_PARTY_MISUSE_CONFIRMED', 'OTHER_FRAUD_CONFIRMED'
    ))
);

CREATE TABLE fraud_labels (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    label_value VARCHAR(16) NOT NULL,
    fraud_type VARCHAR(64),
    evidence_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    source_review_resolution_id UUID NOT NULL,
    actor_operator_id UUID NOT NULL,
    supersedes_label_id UUID,
    reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_labels PRIMARY KEY (id),
    CONSTRAINT uk_fraud_labels_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_fraud_labels_source_resolution UNIQUE (merchant_id, source_review_resolution_id),
    CONSTRAINT uk_fraud_labels_supersedes UNIQUE (merchant_id, supersedes_label_id),
    CONSTRAINT fk_fraud_labels_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_fraud_labels_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT fk_fraud_labels_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT fk_fraud_labels_resolution FOREIGN KEY (merchant_id, source_review_resolution_id)
        REFERENCES review_resolutions (merchant_id, id),
    CONSTRAINT fk_fraud_labels_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_fraud_labels_supersedes FOREIGN KEY (merchant_id, supersedes_label_id)
        REFERENCES fraud_labels (merchant_id, id),
    CONSTRAINT ck_fraud_labels_origin CHECK (traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_fraud_labels_semantics CHECK (
        source = 'MANUAL_REVIEW' AND label_value = 'FRAUD'
        AND fraud_type IN ('STOLEN_PAYMENT_CREDENTIAL', 'ACCOUNT_TAKEOVER', 'CARD_TESTING',
            'FIRST_PARTY_MISUSE', 'OTHER_CONFIRMED_FRAUD')
    ),
    CONSTRAINT ck_fraud_labels_time CHECK (
        evidence_occurred_at <= available_at AND available_at = created_at
    )
);

ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_event_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_event_type CHECK (event_type IN (
    'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
    'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
    'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
    'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created'
));
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_resource_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_resource_type CHECK (
    resource_type IN ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'MANUAL_REVIEW_CASE', 'REFUND', 'FRAUD_LABEL')
);

ALTER TABLE webhook_endpoint_event_subscriptions
    DROP CONSTRAINT ck_webhook_endpoint_event_subscriptions_type;
ALTER TABLE webhook_endpoint_event_subscriptions
    ADD CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (event_type IN (
        'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
        'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
        'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
        'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created'
    ));
