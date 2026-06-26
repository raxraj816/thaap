CREATE TABLE ledger_entries (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    transaction_group_id UUID NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id UUID NOT NULL,
    account VARCHAR(64) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_ledger_entries_payment_intent_source FOREIGN KEY (merchant_id, source_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT uk_ledger_entries_capture_account UNIQUE (merchant_id, source_type, source_id, account),
    CONSTRAINT ck_ledger_entries_source_type CHECK (source_type IN ('PAYMENT_INTENT_CAPTURE')),
    CONSTRAINT ck_ledger_entries_account CHECK (account IN ('CUSTOMER_RECEIVABLE', 'MERCHANT_PAYABLE')),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_capture_pair CHECK (
        (account = 'CUSTOMER_RECEIVABLE' AND direction = 'DEBIT')
        OR (account = 'MERCHANT_PAYABLE' AND direction = 'CREDIT')
    ),
    CONSTRAINT ck_ledger_entries_positive_amount CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_merchant_created ON ledger_entries (merchant_id, created_at, id);
CREATE INDEX idx_ledger_entries_merchant_group ON ledger_entries (merchant_id, transaction_group_id);
CREATE INDEX idx_ledger_entries_merchant_source ON ledger_entries (merchant_id, source_type, source_id);
