ALTER TABLE webhook_endpoints
    ADD COLUMN signing_secret_generation UUID;

UPDATE webhook_endpoints
SET signing_secret_generation = id
WHERE signing_secret_generation IS NULL;

ALTER TABLE webhook_endpoints
    ALTER COLUMN signing_secret_generation SET NOT NULL;

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_prefix VARCHAR(32);

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_hash VARCHAR(64);

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_ciphertext TEXT;

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_nonce VARCHAR(64);

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_key_version VARCHAR(32);

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_generation UUID;

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret_expires_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE webhook_endpoints
    ADD CONSTRAINT ck_webhook_endpoints_previous_secret_state CHECK (
        (
            previous_signing_secret_prefix IS NULL
            AND previous_signing_secret_hash IS NULL
            AND previous_signing_secret_ciphertext IS NULL
            AND previous_signing_secret_nonce IS NULL
            AND previous_signing_secret_key_version IS NULL
            AND previous_signing_secret_generation IS NULL
            AND previous_signing_secret_expires_at IS NULL
        )
        OR
        (
            previous_signing_secret_prefix IS NOT NULL
            AND previous_signing_secret_hash IS NOT NULL
            AND previous_signing_secret_ciphertext IS NOT NULL
            AND previous_signing_secret_nonce IS NOT NULL
            AND previous_signing_secret_key_version IS NOT NULL
            AND previous_signing_secret_generation IS NOT NULL
            AND previous_signing_secret_expires_at IS NOT NULL
        )
    );

CREATE TABLE webhook_endpoint_creation_records (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    webhook_endpoint_id UUID,
    signing_secret_ciphertext TEXT,
    signing_secret_nonce VARCHAR(64),
    signing_secret_key_version VARCHAR(32),
    signing_secret_generation UUID,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_endpoint_creation_records PRIMARY KEY (id),
    CONSTRAINT uk_webhook_endpoint_creation_merchant_key
        UNIQUE (merchant_id, idempotency_key_hash),
    CONSTRAINT uk_webhook_endpoint_creation_endpoint
        UNIQUE (merchant_id, webhook_endpoint_id),
    CONSTRAINT fk_webhook_endpoint_creation_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_webhook_endpoint_creation_endpoint
        FOREIGN KEY (merchant_id, webhook_endpoint_id)
        REFERENCES webhook_endpoints (merchant_id, id),
    CONSTRAINT ck_webhook_endpoint_creation_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_webhook_endpoint_creation_state CHECK (
        (
            status = 'IN_PROGRESS'
            AND webhook_endpoint_id IS NULL
            AND signing_secret_ciphertext IS NULL
            AND signing_secret_nonce IS NULL
            AND signing_secret_key_version IS NULL
            AND signing_secret_generation IS NULL
        )
        OR
        (
            status = 'COMPLETED'
            AND webhook_endpoint_id IS NOT NULL
            AND signing_secret_ciphertext IS NOT NULL
            AND signing_secret_nonce IS NOT NULL
            AND signing_secret_key_version IS NOT NULL
            AND signing_secret_generation IS NOT NULL
        )
    ),
    CONSTRAINT ck_webhook_endpoint_creation_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_webhook_endpoint_creation_merchant_status
    ON webhook_endpoint_creation_records (merchant_id, status, created_at, id);

CREATE TABLE webhook_dispatch_cursor (
    singleton_id INTEGER NOT NULL,
    last_merchant_id UUID,
    last_endpoint_id UUID,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_dispatch_cursor PRIMARY KEY (singleton_id),
    CONSTRAINT ck_webhook_dispatch_cursor_singleton CHECK (singleton_id = 1)
);

INSERT INTO webhook_dispatch_cursor (
    singleton_id,
    last_merchant_id,
    last_endpoint_id,
    updated_at
)
VALUES (1, NULL, NULL, CURRENT_TIMESTAMP);
