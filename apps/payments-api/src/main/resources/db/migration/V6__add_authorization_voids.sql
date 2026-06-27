-- Before Chapter 17, AUTOMATIC was implemented with MANUAL behavior. Preserve
-- the safe, explicit capture requirement for already-authorized local data
-- instead of silently capturing it during a schema migration.
UPDATE payment_intents
SET capture_method = 'MANUAL',
    updated_at = CURRENT_TIMESTAMP
WHERE capture_method = 'AUTOMATIC'
  AND status = 'REQUIRES_CAPTURE';

ALTER TABLE payment_attempts
    ADD CONSTRAINT uk_payment_attempts_merchant_id_intent_id
        UNIQUE (merchant_id, id, payment_intent_id);

ALTER TABLE authorizations
    ADD CONSTRAINT uk_authorizations_merchant_id_id_attempt
        UNIQUE (merchant_id, id, payment_attempt_id);

CREATE TABLE authorization_voids (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    authorization_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_authorization_voids PRIMARY KEY (id),
    CONSTRAINT uk_authorization_voids_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_authorization_voids_authorization UNIQUE (merchant_id, authorization_id),
    CONSTRAINT uk_authorization_voids_payment_intent UNIQUE (merchant_id, payment_intent_id),
    CONSTRAINT fk_authorization_voids_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_authorization_voids_authorization FOREIGN KEY
        (merchant_id, authorization_id, payment_attempt_id)
        REFERENCES authorizations (merchant_id, id, payment_attempt_id),
    CONSTRAINT fk_authorization_voids_attempt FOREIGN KEY
        (merchant_id, payment_attempt_id, payment_intent_id)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id),
    CONSTRAINT ck_authorization_voids_status CHECK (status IN ('RECORDED')),
    CONSTRAINT ck_authorization_voids_reason CHECK (reason IN ('MERCHANT_CANCELED'))
);

CREATE INDEX idx_authorization_voids_merchant_created
    ON authorization_voids (merchant_id, created_at, id);

CREATE INDEX idx_authorization_voids_attempt
    ON authorization_voids (merchant_id, payment_attempt_id);
