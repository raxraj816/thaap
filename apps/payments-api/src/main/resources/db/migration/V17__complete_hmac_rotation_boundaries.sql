ALTER TABLE idempotency_records
    ADD COLUMN request_hash_key_version VARCHAR(32) DEFAULT 'v1';

UPDATE idempotency_records
SET request_hash_key_version = 'v1'
WHERE request_hash_key_version IS NULL;

ALTER TABLE idempotency_records
    ALTER COLUMN request_hash_key_version SET NOT NULL;

ALTER TABLE webhook_endpoint_creation_records
    ADD COLUMN request_hash_key_version VARCHAR(32) DEFAULT 'v1';

UPDATE webhook_endpoint_creation_records
SET request_hash_key_version = 'v1'
WHERE request_hash_key_version IS NULL;

ALTER TABLE webhook_endpoint_creation_records
    ALTER COLUMN request_hash_key_version SET NOT NULL;

ALTER TABLE webhook_endpoint_creation_records
    ADD CONSTRAINT uk_webhook_endpoint_creation_merchant_record
        UNIQUE (merchant_id, id);

CREATE TABLE webhook_endpoint_creation_identities (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    identity_hash VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    creation_record_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_endpoint_creation_identities PRIMARY KEY (id),
    CONSTRAINT uk_webhook_endpoint_creation_identity_merchant_hash
        UNIQUE (merchant_id, identity_hash),
    CONSTRAINT uk_webhook_endpoint_creation_identity_record_version
        UNIQUE (creation_record_id, key_version),
    CONSTRAINT fk_webhook_endpoint_creation_identity_record
        FOREIGN KEY (merchant_id, creation_record_id)
        REFERENCES webhook_endpoint_creation_records (merchant_id, id)
        ON DELETE CASCADE
);

INSERT INTO webhook_endpoint_creation_identities (
    id,
    merchant_id,
    identity_hash,
    key_version,
    creation_record_id,
    created_at
)
SELECT
    id,
    merchant_id,
    idempotency_key_hash,
    request_hash_key_version,
    id,
    created_at
FROM webhook_endpoint_creation_records;

CREATE INDEX idx_webhook_endpoint_creation_identity_record
    ON webhook_endpoint_creation_identities (creation_record_id);
