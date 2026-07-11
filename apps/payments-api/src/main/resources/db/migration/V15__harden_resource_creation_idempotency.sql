ALTER TABLE idempotency_records
    ADD COLUMN lease_token UUID;

ALTER TABLE idempotency_records
    ADD COLUMN lease_expires_at TIMESTAMP(6) WITH TIME ZONE;

UPDATE idempotency_records
SET lease_token = id,
    lease_expires_at = CURRENT_TIMESTAMP
WHERE status = 'IN_PROGRESS';

ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_records_lease CHECK (
        (
            status = 'IN_PROGRESS'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
        OR
        (
            status = 'COMPLETED'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    );

CREATE TABLE merchant_creation_records (
    id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    merchant_id UUID,
    lease_token UUID,
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_creation_records PRIMARY KEY (id),
    CONSTRAINT uk_merchant_creation_key UNIQUE (idempotency_key_hash),
    CONSTRAINT uk_merchant_creation_merchant UNIQUE (merchant_id),
    CONSTRAINT fk_merchant_creation_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT ck_merchant_creation_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_merchant_creation_state CHECK (
        (
            status = 'IN_PROGRESS'
            AND merchant_id IS NULL
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
        OR
        (
            status = 'COMPLETED'
            AND merchant_id IS NOT NULL
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT ck_merchant_creation_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_merchant_creation_status
    ON merchant_creation_records (status, created_at, id);
