CREATE TABLE payment_investigation_relation_anchors (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    relation_identity_hmac VARCHAR(255) NOT NULL,
    identity_key_version VARCHAR(32) NOT NULL,
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_investigation_relation_anchors PRIMARY KEY (id),
    CONSTRAINT uk_payment_investigation_relation_anchors_id
        UNIQUE (merchant_id, id),
    CONSTRAINT uk_payment_investigation_relation_alias
        UNIQUE (merchant_id, payment_attempt_id, relation_type,
                relation_identity_hmac, identity_key_version),
    CONSTRAINT fk_payment_investigation_relation_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_payment_investigation_relation_payment
        FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_payment_investigation_relation_attempt
        FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT ck_payment_investigation_relation_type CHECK (relation_type IN (
        'SAME_CARD_FINGERPRINT', 'SAME_EMAIL', 'SAME_PHONE',
        'SAME_DEVICE', 'SAME_SESSION', 'SAME_IP')),
    CONSTRAINT ck_payment_investigation_relation_digest CHECK (
        CHAR_LENGTH(relation_identity_hmac) BETWEEN 32 AND 255
        AND relation_identity_hmac = TRIM(relation_identity_hmac)),
    CONSTRAINT ck_payment_investigation_relation_key CHECK (
        CHAR_LENGTH(identity_key_version) BETWEEN 1 AND 32
        AND identity_key_version = TRIM(identity_key_version)),
    CONSTRAINT ck_payment_investigation_relation_origin CHECK (
        traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_payment_investigation_relation_source CHECK (
        (relation_type IN ('SAME_EMAIL', 'SAME_PHONE') AND source_type = 'CUSTOMER')
        OR (relation_type = 'SAME_CARD_FINGERPRINT' AND source_type = 'PAYMENT_METHOD')
        OR (relation_type IN ('SAME_DEVICE', 'SAME_SESSION', 'SAME_IP')
            AND source_type = 'RISK_CONTEXT')),
    CONSTRAINT ck_payment_investigation_relation_time CHECK (created_at >= observed_at)
);

CREATE INDEX idx_payment_investigation_relation_lookup
    ON payment_investigation_relation_anchors
        (merchant_id, relation_type, relation_identity_hmac,
         observed_at DESC, payment_intent_id);

CREATE INDEX idx_payment_investigation_relation_payment
    ON payment_investigation_relation_anchors
        (merchant_id, payment_intent_id, relation_type, identity_key_version);

