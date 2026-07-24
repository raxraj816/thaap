ALTER TABLE risk_contexts ADD COLUMN ip_identity_hmac_aliases TEXT;
ALTER TABLE risk_contexts ADD COLUMN device_identity_hmac_aliases TEXT;
ALTER TABLE risk_contexts ADD COLUMN session_identity_hmac_aliases TEXT;

ALTER TABLE idempotency_records ADD COLUMN principal_type VARCHAR(32);
ALTER TABLE idempotency_records ADD COLUMN principal_id UUID;
ALTER TABLE idempotency_records ADD COLUMN principal_credential_prefix VARCHAR(32);

UPDATE idempotency_records
SET principal_type = 'MERCHANT_API',
    principal_id = merchant_id,
    principal_credential_prefix = (
        SELECT merchant.api_key_prefix
        FROM merchants merchant
        WHERE merchant.id = idempotency_records.merchant_id
    );

ALTER TABLE idempotency_records ALTER COLUMN principal_type SET NOT NULL;
ALTER TABLE idempotency_records ALTER COLUMN principal_id SET NOT NULL;
ALTER TABLE idempotency_records ALTER COLUMN principal_credential_prefix SET NOT NULL;

ALTER TABLE idempotency_records DROP CONSTRAINT uk_idempotency_merchant_key;
ALTER TABLE idempotency_records ADD CONSTRAINT uk_idempotency_principal_key UNIQUE
    (merchant_id, principal_type, principal_id, principal_credential_prefix, idempotency_key);
ALTER TABLE idempotency_records ADD CONSTRAINT ck_idempotency_principal_type CHECK
    (principal_type IN ('MERCHANT_API', 'RISK_OPERATOR'));
ALTER TABLE idempotency_records ADD CONSTRAINT ck_idempotency_merchant_principal CHECK
    (principal_type <> 'MERCHANT_API' OR principal_id = merchant_id);
