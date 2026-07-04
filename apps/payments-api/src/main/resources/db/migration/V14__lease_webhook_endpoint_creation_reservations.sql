ALTER TABLE webhook_endpoint_creation_records
    ADD COLUMN lease_token UUID;

ALTER TABLE webhook_endpoint_creation_records
    ADD COLUMN lease_expires_at TIMESTAMP(6) WITH TIME ZONE;

UPDATE webhook_endpoint_creation_records
SET lease_token = id,
    lease_expires_at = CURRENT_TIMESTAMP
WHERE status = 'IN_PROGRESS';

ALTER TABLE webhook_endpoint_creation_records
    ADD CONSTRAINT ck_webhook_endpoint_creation_lease CHECK (
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
