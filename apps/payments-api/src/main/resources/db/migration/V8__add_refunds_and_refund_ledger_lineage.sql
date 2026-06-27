-- Chapter 18 cannot reconstruct the random response ID, authorization lineage,
-- or ledger truth for refunds created by the earlier thin endpoint. Refuse the
-- local upgrade instead of fabricating money history. Local environments with
-- these rows must be reset or explicitly remediated before retrying V8.
CREATE TABLE chapter18_legacy_refund_guard (
    valid BOOLEAN NOT NULL,
    CONSTRAINT ck_chapter18_no_legacy_refunded_payment_intents CHECK (valid)
);

INSERT INTO chapter18_legacy_refund_guard (valid)
SELECT FALSE
FROM payment_intents
WHERE status = 'REFUNDED';

DROP TABLE chapter18_legacy_refund_guard;

CREATE TABLE refunds (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    authorization_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT uk_refunds_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_refunds_merchant_id_id_payment_intent
        UNIQUE (merchant_id, id, payment_intent_id),
    CONSTRAINT uk_refunds_payment_intent UNIQUE (merchant_id, payment_intent_id),
    CONSTRAINT fk_refunds_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_refunds_payment_intent FOREIGN KEY
        (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_refunds_attempt FOREIGN KEY
        (merchant_id, payment_attempt_id, payment_intent_id)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id),
    CONSTRAINT fk_refunds_authorization FOREIGN KEY
        (merchant_id, authorization_id, payment_attempt_id)
        REFERENCES authorizations (merchant_id, id, payment_attempt_id),
    CONSTRAINT ck_refunds_positive_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('RECORDED'))
);

CREATE INDEX idx_refunds_merchant_created
    ON refunds (merchant_id, created_at, id);

CREATE INDEX idx_refunds_attempt
    ON refunds (merchant_id, payment_attempt_id);

ALTER TABLE ledger_entries
    ADD COLUMN payment_intent_id UUID;

ALTER TABLE ledger_entries
    ADD COLUMN refund_id UUID;

UPDATE ledger_entries
SET payment_intent_id = source_id
WHERE source_type = 'PAYMENT_INTENT_CAPTURE';

ALTER TABLE ledger_entries
    ALTER COLUMN payment_intent_id SET NOT NULL;

ALTER TABLE ledger_entries
    DROP CONSTRAINT fk_ledger_entries_payment_intent_source;

ALTER TABLE ledger_entries
    DROP CONSTRAINT uk_ledger_entries_capture_account;

ALTER TABLE ledger_entries
    DROP CONSTRAINT ck_ledger_entries_source_type;

ALTER TABLE ledger_entries
    DROP CONSTRAINT ck_ledger_entries_capture_pair;

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_entries_payment_intent FOREIGN KEY
        (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id);

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_entries_refund FOREIGN KEY
        (merchant_id, refund_id, payment_intent_id)
        REFERENCES refunds (merchant_id, id, payment_intent_id);

ALTER TABLE ledger_entries
    ADD CONSTRAINT uk_ledger_entries_source_account
        UNIQUE (merchant_id, source_type, source_id, account);

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_source_type
        CHECK (source_type IN ('PAYMENT_INTENT_CAPTURE', 'REFUND'));

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_source_posting CHECK (
        (
            source_type = 'PAYMENT_INTENT_CAPTURE'
            AND refund_id IS NULL
            AND source_id = payment_intent_id
            AND (
                (account = 'CUSTOMER_RECEIVABLE' AND direction = 'DEBIT')
                OR (account = 'MERCHANT_PAYABLE' AND direction = 'CREDIT')
            )
        )
        OR
        (
            source_type = 'REFUND'
            AND refund_id IS NOT NULL
            AND source_id = refund_id
            AND (
                (account = 'MERCHANT_PAYABLE' AND direction = 'DEBIT')
                OR (account = 'CUSTOMER_RECEIVABLE' AND direction = 'CREDIT')
            )
        )
    );

CREATE INDEX idx_ledger_entries_merchant_payment_intent
    ON ledger_entries (merchant_id, payment_intent_id, created_at, id);

CREATE INDEX idx_ledger_entries_merchant_refund
    ON ledger_entries (merchant_id, refund_id, created_at, id);
