ALTER TABLE merchant_creation_records
    ADD COLUMN derivation_key_version VARCHAR(32);

UPDATE merchant_creation_records
SET derivation_key_version = 'v1'
WHERE derivation_key_version IS NULL;

ALTER TABLE merchant_creation_records
    ALTER COLUMN derivation_key_version SET NOT NULL;

CREATE TABLE merchant_creation_identities (
    identity_hash VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    merchant_creation_record_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_creation_identities PRIMARY KEY (identity_hash),
    CONSTRAINT uk_merchant_creation_identity_record_version
        UNIQUE (merchant_creation_record_id, key_version),
    CONSTRAINT fk_merchant_creation_identity_record
        FOREIGN KEY (merchant_creation_record_id)
        REFERENCES merchant_creation_records (id)
        ON DELETE CASCADE
);

INSERT INTO merchant_creation_identities (
    identity_hash,
    key_version,
    merchant_creation_record_id,
    created_at
)
SELECT
    idempotency_key_hash,
    derivation_key_version,
    id,
    created_at
FROM merchant_creation_records;

CREATE INDEX idx_merchant_creation_identity_record
    ON merchant_creation_identities (merchant_creation_record_id);
