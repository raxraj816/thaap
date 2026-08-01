ALTER TABLE audit_logs ALTER COLUMN merchant_id DROP NOT NULL;
ALTER TABLE audit_logs ADD CONSTRAINT ck_audit_logs_actor_scope CHECK (
    (actor_type = 'PLATFORM_OPERATOR' AND merchant_id IS NULL)
    OR (actor_type = 'PLATFORM_BOOTSTRAP' AND merchant_id IS NULL)
    OR (actor_type IN ('MERCHANT_API_KEY', 'RISK_OPERATOR') AND merchant_id IS NOT NULL));

CREATE TABLE platform_operators (
    id UUID NOT NULL,
    operator_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    lifecycle_epoch BIGINT NOT NULL,
    created_by_operator_id UUID,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_platform_operators PRIMARY KEY (id),
    CONSTRAINT uk_platform_operators_code UNIQUE (operator_code),
    CONSTRAINT fk_platform_operators_creator FOREIGN KEY (created_by_operator_id)
        REFERENCES platform_operators (id),
    CONSTRAINT ck_platform_operators_role CHECK
        (role IN ('DATA_OPERATOR', 'PLATFORM_ADMIN')),
    CONSTRAINT ck_platform_operators_status CHECK
        (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_platform_operators_epoch CHECK (lifecycle_epoch > 0),
    CONSTRAINT ck_platform_operators_lifecycle CHECK (
        (status = 'ACTIVE' AND disabled_at IS NULL)
        OR (status = 'DISABLED' AND disabled_at IS NOT NULL))
);

CREATE TABLE platform_operator_credentials (
    id UUID NOT NULL,
    platform_operator_id UUID NOT NULL,
    secret_prefix VARCHAR(32) NOT NULL,
    secret_sha256 VARCHAR(64) NOT NULL,
    secret_identity_hmac VARCHAR(64) NOT NULL,
    secret_hmac_key_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_platform_operator_credentials PRIMARY KEY (id),
    CONSTRAINT uk_platform_operator_credentials_operator UNIQUE (platform_operator_id),
    CONSTRAINT uk_platform_operator_credentials_identity UNIQUE (secret_identity_hmac),
    CONSTRAINT fk_platform_operator_credentials_operator FOREIGN KEY (platform_operator_id)
        REFERENCES platform_operators (id),
    CONSTRAINT ck_platform_operator_credentials_digest CHECK (
        LENGTH(secret_sha256) = 64 AND LENGTH(secret_identity_hmac) = 64),
    CONSTRAINT ck_platform_operator_credentials_key_version CHECK (
        LENGTH(secret_hmac_key_version) BETWEEN 1 AND 32)
);

CREATE TABLE platform_operator_bootstrap_state (
    singleton_id SMALLINT NOT NULL,
    bootstrap_completed BOOLEAN NOT NULL,
    first_admin_id UUID,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_platform_operator_bootstrap_state PRIMARY KEY (singleton_id),
    CONSTRAINT fk_platform_operator_bootstrap_admin FOREIGN KEY (first_admin_id)
        REFERENCES platform_operators (id),
    CONSTRAINT ck_platform_operator_bootstrap_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_platform_operator_bootstrap_completed CHECK (
        (bootstrap_completed = FALSE AND first_admin_id IS NULL)
        OR (bootstrap_completed = TRUE AND first_admin_id IS NOT NULL))
);

INSERT INTO platform_operator_bootstrap_state (
    singleton_id, bootstrap_completed, first_admin_id, updated_at)
VALUES (1, FALSE, NULL, CURRENT_TIMESTAMP);

CREATE TABLE platform_operator_requests (
    id UUID NOT NULL,
    principal_kind VARCHAR(24) NOT NULL,
    principal_operator_id UUID,
    principal_credential_id UUID,
    principal_credential_prefix VARCHAR(32),
    operation VARCHAR(48) NOT NULL,
    request_identity_hmac VARCHAR(64) NOT NULL,
    request_identity_key_version VARCHAR(32) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    request_digest_key_version VARCHAR(32) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    result_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_platform_operator_requests PRIMARY KEY (id),
    CONSTRAINT uk_platform_operator_requests_identity UNIQUE (request_identity_hmac),
    CONSTRAINT fk_platform_operator_requests_principal_operator
        FOREIGN KEY (principal_operator_id) REFERENCES platform_operators (id),
    CONSTRAINT fk_platform_operator_requests_principal_credential
        FOREIGN KEY (principal_credential_id) REFERENCES platform_operator_credentials (id),
    CONSTRAINT fk_platform_operator_requests_result
        FOREIGN KEY (result_operator_id) REFERENCES platform_operators (id),
    CONSTRAINT ck_platform_operator_requests_kind CHECK
        (principal_kind IN ('BOOTSTRAP', 'PLATFORM_OPERATOR')),
    CONSTRAINT ck_platform_operator_requests_operation CHECK
        (operation IN ('BOOTSTRAP', 'CREATE_OPERATOR', 'DISABLE_OPERATOR')),
    CONSTRAINT ck_platform_operator_requests_principal CHECK (
        (principal_kind = 'BOOTSTRAP' AND principal_operator_id IS NULL
            AND principal_credential_id IS NULL AND principal_credential_prefix IS NULL)
        OR (principal_kind = 'PLATFORM_OPERATOR' AND principal_operator_id IS NOT NULL
            AND principal_credential_id IS NOT NULL
            AND principal_credential_prefix IS NOT NULL)),
    CONSTRAINT ck_platform_operator_requests_digest CHECK (
        LENGTH(request_identity_hmac) = 64 AND LENGTH(request_digest) = 64),
    CONSTRAINT ck_platform_operator_requests_key_versions CHECK (
        LENGTH(request_identity_key_version) BETWEEN 1 AND 32
        AND LENGTH(request_digest_key_version) BETWEEN 1 AND 32),
    CONSTRAINT ck_platform_operator_requests_status CHECK (lifecycle_status = 'COMPLETED'),
    CONSTRAINT ck_platform_operator_requests_time CHECK (completed_at >= created_at)
);

CREATE TABLE event_stream_heads (
    merchant_id UUID NOT NULL,
    stream_type VARCHAR(32) NOT NULL,
    stream_id UUID NOT NULL,
    current_version BIGINT NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_stream_heads PRIMARY KEY (merchant_id, stream_type, stream_id),
    CONSTRAINT fk_event_stream_heads_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT ck_event_stream_heads_type CHECK (stream_type IN (
        'PAYMENT', 'RISK_CONTROL', 'RISK_INCIDENT', 'DISPUTE',
        'WEBHOOK_ENDPOINT', 'DEVELOPER_SCENARIO')),
    CONSTRAINT ck_event_stream_heads_version CHECK (current_version >= 0)
);

CREATE TABLE domain_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    stream_type VARCHAR(32) NOT NULL,
    stream_id UUID NOT NULL,
    stream_version BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_event_id UUID,
    request_id VARCHAR(128),
    trace_id VARCHAR(128),
    actor_type VARCHAR(32),
    actor_id VARCHAR(128),
    traffic_origin VARCHAR(32),
    payload_bytes BYTEA NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    event_record_bytes BYTEA NOT NULL,
    event_record_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT pk_domain_events PRIMARY KEY (id),
    CONSTRAINT uk_domain_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_domain_events_stream_version UNIQUE
        (merchant_id, stream_type, stream_id, stream_version),
    CONSTRAINT fk_domain_events_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_domain_events_stream FOREIGN KEY (merchant_id, stream_type, stream_id)
        REFERENCES event_stream_heads (merchant_id, stream_type, stream_id),
    CONSTRAINT fk_domain_events_causation FOREIGN KEY (merchant_id, causation_event_id)
        REFERENCES domain_events (merchant_id, id),
    CONSTRAINT ck_domain_events_stream_type CHECK (stream_type IN (
        'PAYMENT', 'RISK_CONTROL', 'RISK_INCIDENT', 'DISPUTE',
        'WEBHOOK_ENDPOINT', 'DEVELOPER_SCENARIO')),
    CONSTRAINT ck_domain_events_event_type CHECK (event_type IN (
        'payment_intent.created', 'payment_intent.canceled',
        'payment_attempt.completed', 'fraud_decision.created',
        'authorization.created', 'authorization.voided',
        'capture.created', 'refund.created', 'chargeback.created',
        'authentication_challenge.created', 'authentication_challenge.completed',
        'manual_review.opened', 'manual_review.assigned', 'manual_review.resolved',
        'fraud_label.recorded', 'fraud_label.superseded',
        'risk_incident.opened', 'risk_incident.resolved',
        'merchant_risk_profile.activated', 'merchant_risk_profile.rolled_back',
        'risk_policy.activated', 'risk_policy.rolled_back',
        'authored_rule.activated', 'authored_rule.rolled_back', 'authored_rule.disabled',
        'safety_exception.activated', 'safety_exception.expired', 'safety_exception.disabled',
        'dispute_workflow.opened', 'dispute_evidence.submitted',
        'dispute_outcome.recorded', 'dispute_workflow.expired',
        'webhook_endpoint.verified', 'webhook_endpoint.disabled',
        'webhook_endpoint.secret_rotated', 'webhook_delivery.exhausted',
        'webhook_redelivery.requested', 'webhook_redelivery.succeeded',
        'post_authorization_signal.recorded',
        'developer_scenario.created', 'developer_scenario.completed',
        'developer_scenario.failed')),
    CONSTRAINT ck_domain_events_stream_version CHECK (stream_version > 0),
    CONSTRAINT ck_domain_events_event_version CHECK (event_version = 1),
    CONSTRAINT ck_domain_events_time_order CHECK (recorded_at >= occurred_at),
    CONSTRAINT ck_domain_events_actor_pair CHECK (
        (actor_type IS NULL AND actor_id IS NULL)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL)),
    CONSTRAINT ck_domain_events_actor_type CHECK (
        actor_type IS NULL OR actor_type IN ('MERCHANT_API_KEY', 'RISK_OPERATOR',
            'PLATFORM_OPERATOR', 'SYSTEM')),
    CONSTRAINT ck_domain_events_origin CHECK (
        traffic_origin IS NULL OR traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_domain_events_payload_size CHECK
        (OCTET_LENGTH(payload_bytes) BETWEEN 2 AND 32768),
    CONSTRAINT ck_domain_events_record_size CHECK
        (OCTET_LENGTH(event_record_bytes) BETWEEN 2 AND 65536),
    CONSTRAINT ck_domain_events_digests CHECK (
        LENGTH(payload_sha256) = 64 AND LENGTH(event_record_sha256) = 64)
);

CREATE TABLE event_publications (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    domain_event_id UUID NOT NULL,
    lifecycle_status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_publications PRIMARY KEY (id),
    CONSTRAINT uk_event_publications_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_event_publications_event UNIQUE (merchant_id, domain_event_id),
    CONSTRAINT fk_event_publications_event FOREIGN KEY (merchant_id, domain_event_id)
        REFERENCES domain_events (merchant_id, id),
    CONSTRAINT ck_event_publications_status CHECK (lifecycle_status = 'PENDING')
);

CREATE INDEX idx_platform_operators_status
    ON platform_operators (status, role, created_at, id);
CREATE INDEX idx_platform_operator_credentials_prefix
    ON platform_operator_credentials (secret_prefix);
CREATE INDEX idx_platform_operator_requests_principal
    ON platform_operator_requests
        (principal_kind, principal_operator_id, principal_credential_id, created_at, id);
CREATE INDEX idx_domain_events_merchant_recorded
    ON domain_events (merchant_id, recorded_at, id);
CREATE INDEX idx_domain_events_stream
    ON domain_events (merchant_id, stream_type, stream_id, stream_version);
CREATE INDEX idx_domain_events_type_recorded
    ON domain_events (event_type, event_version, recorded_at, id);
CREATE INDEX idx_event_publications_pending
    ON event_publications (lifecycle_status, created_at, id);
CREATE INDEX idx_event_publications_merchant_status
    ON event_publications (merchant_id, lifecycle_status, created_at, id);
