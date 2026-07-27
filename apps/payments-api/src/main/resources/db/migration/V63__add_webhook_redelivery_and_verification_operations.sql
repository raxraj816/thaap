ALTER TABLE webhook_deliveries ADD COLUMN delivery_generation INTEGER DEFAULT 1;
ALTER TABLE webhook_deliveries ADD COLUMN generation_attempt_count INTEGER DEFAULT 0;
UPDATE webhook_deliveries SET generation_attempt_count = attempt_count;
ALTER TABLE webhook_deliveries ALTER COLUMN delivery_generation SET NOT NULL;
ALTER TABLE webhook_deliveries ALTER COLUMN generation_attempt_count SET NOT NULL;
ALTER TABLE webhook_deliveries ALTER COLUMN delivery_generation DROP DEFAULT;
ALTER TABLE webhook_deliveries ALTER COLUMN generation_attempt_count DROP DEFAULT;
ALTER TABLE webhook_deliveries ADD CONSTRAINT ck_webhook_deliveries_generation
    CHECK (delivery_generation > 0 AND generation_attempt_count >= 0
        AND generation_attempt_count <= attempt_count);

ALTER TABLE webhook_delivery_attempts ADD COLUMN delivery_generation INTEGER;
ALTER TABLE webhook_delivery_attempts ADD COLUMN generation_attempt_number INTEGER;
ALTER TABLE webhook_delivery_attempts ADD COLUMN dispatch_source VARCHAR(32) DEFAULT 'AUTOMATIC_RETRY';
ALTER TABLE webhook_delivery_attempts ADD COLUMN event_payload_sha256 VARCHAR(64);
ALTER TABLE webhook_delivery_attempts ADD COLUMN signing_secret_generation UUID;
UPDATE webhook_delivery_attempts SET delivery_generation = 1;
UPDATE webhook_delivery_attempts SET generation_attempt_number = attempt_number;
UPDATE webhook_delivery_attempts attempt
SET event_payload_sha256 = (
    SELECT event.payload_sha256
    FROM webhook_deliveries delivery
    JOIN webhook_events event
      ON event.merchant_id = delivery.merchant_id
     AND event.id = delivery.webhook_event_id
    WHERE delivery.merchant_id = attempt.merchant_id
      AND delivery.id = attempt.webhook_delivery_id
);
ALTER TABLE webhook_delivery_attempts ALTER COLUMN delivery_generation SET NOT NULL;
ALTER TABLE webhook_delivery_attempts ALTER COLUMN generation_attempt_number SET NOT NULL;
ALTER TABLE webhook_delivery_attempts ALTER COLUMN dispatch_source SET NOT NULL;
ALTER TABLE webhook_delivery_attempts ALTER COLUMN event_payload_sha256 SET NOT NULL;
ALTER TABLE webhook_delivery_attempts ALTER COLUMN dispatch_source DROP DEFAULT;
ALTER TABLE webhook_delivery_attempts ADD CONSTRAINT uk_webhook_attempts_delivery_generation_number
    UNIQUE (webhook_delivery_id, delivery_generation, generation_attempt_number);
ALTER TABLE webhook_delivery_attempts ADD CONSTRAINT ck_webhook_attempts_generation
    CHECK (delivery_generation > 0 AND generation_attempt_number > 0);
ALTER TABLE webhook_delivery_attempts ADD CONSTRAINT ck_webhook_attempts_source
    CHECK (dispatch_source IN ('AUTOMATIC_RETRY', 'MANUAL_REDELIVERY'));
ALTER TABLE webhook_delivery_attempts ADD CONSTRAINT ck_webhook_attempts_payload_digest
    CHECK (LENGTH(event_payload_sha256) = 64);

CREATE TABLE webhook_redelivery_requests (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    webhook_delivery_id UUID NOT NULL,
    webhook_event_id UUID NOT NULL,
    webhook_endpoint_id UUID NOT NULL,
    requested_generation INTEGER NOT NULL,
    actor_type VARCHAR(24) NOT NULL,
    actor_operator_id UUID,
    actor_role VARCHAR(32) NOT NULL,
    request_reason VARCHAR(32) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_redelivery_requests PRIMARY KEY (id),
    CONSTRAINT uk_webhook_redelivery_requests_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_webhook_redelivery_requests_generation UNIQUE
        (merchant_id, webhook_delivery_id, requested_generation),
    CONSTRAINT fk_webhook_redelivery_requests_delivery FOREIGN KEY
        (merchant_id, webhook_delivery_id) REFERENCES webhook_deliveries (merchant_id, id),
    CONSTRAINT fk_webhook_redelivery_requests_event FOREIGN KEY
        (merchant_id, webhook_event_id) REFERENCES webhook_events (merchant_id, id),
    CONSTRAINT fk_webhook_redelivery_requests_endpoint FOREIGN KEY
        (merchant_id, webhook_endpoint_id) REFERENCES webhook_endpoints (merchant_id, id),
    CONSTRAINT fk_webhook_redelivery_requests_actor FOREIGN KEY
        (merchant_id, actor_operator_id) REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_webhook_redelivery_requests_generation CHECK (requested_generation > 1),
    CONSTRAINT ck_webhook_redelivery_requests_actor CHECK (
        (actor_type = 'MERCHANT_API' AND actor_operator_id IS NULL AND actor_role = 'MERCHANT')
        OR (actor_type = 'RISK_OPERATOR' AND actor_operator_id IS NOT NULL
            AND actor_role IN ('ANALYST', 'RISK_MANAGER'))),
    CONSTRAINT ck_webhook_redelivery_requests_reason CHECK
        (request_reason IN ('MANUAL_RETRY', 'ENDPOINT_RECOVERED', 'RECEIVER_REPAIRED')),
    CONSTRAINT ck_webhook_redelivery_requests_digest CHECK (LENGTH(request_digest) = 64),
    CONSTRAINT ck_webhook_redelivery_requests_status CHECK (lifecycle_status = 'SCHEDULED')
);

ALTER TABLE webhook_delivery_attempts ADD COLUMN redelivery_request_id UUID;
ALTER TABLE webhook_delivery_attempts ADD CONSTRAINT fk_webhook_attempts_redelivery_request
    FOREIGN KEY (merchant_id, redelivery_request_id)
    REFERENCES webhook_redelivery_requests (merchant_id, id);
ALTER TABLE webhook_delivery_attempts ADD CONSTRAINT ck_webhook_attempts_redelivery_source CHECK (
    (dispatch_source = 'AUTOMATIC_RETRY' AND redelivery_request_id IS NULL)
    OR (dispatch_source = 'MANUAL_REDELIVERY' AND redelivery_request_id IS NOT NULL));

ALTER TABLE webhook_endpoints ADD COLUMN verification_status VARCHAR(24) DEFAULT 'NOT_REQUESTED';
ALTER TABLE webhook_endpoints ADD COLUMN pending_verification_id UUID;
ALTER TABLE webhook_endpoints ADD COLUMN verified_url VARCHAR(2048);
ALTER TABLE webhook_endpoints ADD COLUMN verified_signing_secret_generation UUID;
ALTER TABLE webhook_endpoints ADD COLUMN verified_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE webhook_endpoints ALTER COLUMN verification_status SET NOT NULL;
ALTER TABLE webhook_endpoints ALTER COLUMN verification_status DROP DEFAULT;
ALTER TABLE webhook_endpoints ADD CONSTRAINT ck_webhook_endpoints_verification_status
    CHECK (verification_status IN ('NOT_REQUESTED', 'PENDING', 'VERIFIED', 'FAILED', 'EXPIRED'));
ALTER TABLE webhook_endpoints ADD CONSTRAINT ck_webhook_endpoints_verification_evidence CHECK (
    (verification_status = 'PENDING' AND pending_verification_id IS NOT NULL
        AND verified_url IS NULL AND verified_signing_secret_generation IS NULL
        AND verified_at IS NULL)
    OR (verification_status = 'VERIFIED' AND pending_verification_id IS NULL
        AND verified_url IS NOT NULL
        AND verified_signing_secret_generation IS NOT NULL AND verified_at IS NOT NULL)
    OR (verification_status NOT IN ('PENDING', 'VERIFIED')
        AND pending_verification_id IS NULL AND verified_url IS NULL
        AND verified_signing_secret_generation IS NULL AND verified_at IS NULL));

CREATE TABLE webhook_endpoint_verifications (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    webhook_endpoint_id UUID NOT NULL,
    normalized_url VARCHAR(2048) NOT NULL,
    signing_secret_generation UUID NOT NULL,
    nonce_sha256 VARCHAR(64) NOT NULL,
    request_identity_hmac VARCHAR(64) NOT NULL,
    request_identity_key_version VARCHAR(32) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    dispatch_started_at TIMESTAMP(6) WITH TIME ZONE,
    response_status INTEGER,
    failure_code VARCHAR(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_webhook_endpoint_verifications PRIMARY KEY (id),
    CONSTRAINT uk_webhook_endpoint_verifications_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_webhook_endpoint_verifications_request_identity UNIQUE
        (merchant_id, webhook_endpoint_id, request_identity_hmac),
    CONSTRAINT fk_webhook_endpoint_verifications_endpoint FOREIGN KEY
        (merchant_id, webhook_endpoint_id) REFERENCES webhook_endpoints (merchant_id, id),
    CONSTRAINT ck_webhook_endpoint_verifications_nonce CHECK
        (LENGTH(nonce_sha256) = 64 AND LENGTH(request_identity_hmac) = 64),
    CONSTRAINT ck_webhook_endpoint_verifications_status CHECK
        (lifecycle_status IN ('PENDING', 'VERIFIED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_webhook_endpoint_verifications_failure CHECK
        (failure_code IS NULL OR failure_code IN ('TARGET_REJECTED', 'TRANSPORT_FAILED',
            'NON_SUCCESS_STATUS', 'MISSING_RESPONSE_FIELD', 'TOKEN_MISMATCH',
            'ENDPOINT_CHANGED', 'ENDPOINT_DISABLED', 'EXPIRED', 'RECOVERY_REQUIRED')),
    CONSTRAINT ck_webhook_endpoint_verifications_response CHECK
        (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT ck_webhook_endpoint_verifications_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_webhook_endpoint_verifications_state CHECK (
        (lifecycle_status = 'PENDING' AND completed_at IS NULL AND failure_code IS NULL)
        OR (lifecycle_status = 'VERIFIED' AND completed_at IS NOT NULL
            AND response_status BETWEEN 200 AND 299 AND failure_code IS NULL)
        OR (lifecycle_status IN ('FAILED', 'EXPIRED')
            AND completed_at IS NOT NULL AND failure_code IS NOT NULL))
);

CREATE TABLE webhook_endpoint_verification_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    webhook_endpoint_verification_id UUID NOT NULL,
    event_sequence INTEGER NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    outcome_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_endpoint_verification_events PRIMARY KEY (id),
    CONSTRAINT uk_webhook_endpoint_verification_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_webhook_endpoint_verification_events_sequence UNIQUE
        (merchant_id, webhook_endpoint_verification_id, event_sequence),
    CONSTRAINT fk_webhook_endpoint_verification_events_verification FOREIGN KEY
        (merchant_id, webhook_endpoint_verification_id)
        REFERENCES webhook_endpoint_verifications (merchant_id, id),
    CONSTRAINT ck_webhook_endpoint_verification_events_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_webhook_endpoint_verification_events_type CHECK
        (event_type IN ('CHALLENGE_CREATED', 'DISPATCH_STARTED', 'VERIFIED', 'FAILED', 'EXPIRED'))
);

ALTER TABLE webhook_endpoints ADD CONSTRAINT fk_webhook_endpoints_pending_verification
    FOREIGN KEY (merchant_id, pending_verification_id)
    REFERENCES webhook_endpoint_verifications (merchant_id, id);

CREATE INDEX idx_webhook_deliveries_dead_letters
    ON webhook_deliveries (merchant_id, status, created_at, id);
CREATE INDEX idx_webhook_redelivery_requests_history
    ON webhook_redelivery_requests (merchant_id, webhook_delivery_id, requested_generation);
CREATE INDEX idx_webhook_verifications_endpoint_history
    ON webhook_endpoint_verifications (merchant_id, webhook_endpoint_id, created_at, id);
CREATE INDEX idx_webhook_verifications_expiry
    ON webhook_endpoint_verifications (lifecycle_status, expires_at, id);
CREATE INDEX idx_webhook_verification_events_history
    ON webhook_endpoint_verification_events
        (merchant_id, webhook_endpoint_verification_id, event_sequence);
