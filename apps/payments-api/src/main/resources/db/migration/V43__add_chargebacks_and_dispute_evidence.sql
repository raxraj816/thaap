CREATE TABLE chargebacks (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    external_event_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    authorization_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    allocated_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_attestation VARCHAR(64) NOT NULL,
    traffic_origin VARCHAR(32) NOT NULL,
    evidence_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    evidence_due_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_chargebacks PRIMARY KEY (id),
    CONSTRAINT uk_chargebacks_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_chargebacks_merchant_id_id_intent UNIQUE (merchant_id, id, payment_intent_id),
    CONSTRAINT uk_chargebacks_external_event UNIQUE (merchant_id, external_event_key),
    CONSTRAINT fk_chargebacks_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_chargebacks_attempt FOREIGN KEY (merchant_id, payment_attempt_id, payment_intent_id)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id),
    CONSTRAINT fk_chargebacks_authorization FOREIGN KEY (merchant_id, authorization_id, payment_attempt_id)
        REFERENCES authorizations (merchant_id, id, payment_attempt_id),
    CONSTRAINT fk_chargebacks_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT ck_chargebacks_amount CHECK (amount > 0 AND allocated_amount >= 0 AND allocated_amount <= amount),
    CONSTRAINT ck_chargebacks_currency CHECK (CHAR_LENGTH(currency) = 3 AND currency = UPPER(currency)),
    CONSTRAINT ck_chargebacks_reason CHECK (reason_code IN (
        'FRAUDULENT_STOLEN_CREDENTIAL', 'FRAUDULENT_ACCOUNT_TAKEOVER',
        'FRAUDULENT_CARD_TESTING', 'FRAUDULENT_FIRST_PARTY_MISUSE',
        'PRODUCT_NOT_RECEIVED', 'DUPLICATE', 'CREDIT_NOT_PROCESSED', 'GENERAL'
    )),
    CONSTRAINT ck_chargebacks_status CHECK (
        (status = 'ALLOCATED' AND allocated_amount > 0)
        OR (status = 'OFFSET_BY_REFUND' AND allocated_amount = 0)
    ),
    CONSTRAINT ck_chargebacks_source CHECK (source_attestation = 'SIMULATOR_MERCHANT_INGESTED'),
    CONSTRAINT ck_chargebacks_origin CHECK (traffic_origin IN ('MERCHANT_API', 'SYNTHETIC_SCENARIO')),
    CONSTRAINT ck_chargebacks_time CHECK (
        evidence_occurred_at <= received_at
        AND received_at <= available_at
        AND received_at <= evidence_due_at
        AND available_at = created_at
    )
);

CREATE INDEX idx_chargebacks_merchant_created ON chargebacks (merchant_id, created_at, id);
CREATE INDEX idx_chargebacks_intent ON chargebacks (merchant_id, payment_intent_id, created_at, id);
CREATE INDEX idx_chargebacks_due ON chargebacks (merchant_id, evidence_due_at, id);

CREATE TABLE dispute_offset_allocations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    chargeback_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    refund_id UUID,
    captured_amount BIGINT NOT NULL,
    prior_refund_amount BIGINT NOT NULL,
    prior_dispute_amount BIGINT NOT NULL,
    allocated_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    result VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_dispute_offset_allocations PRIMARY KEY (id),
    CONSTRAINT uk_dispute_allocations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_dispute_allocations_chargeback UNIQUE (merchant_id, chargeback_id),
    CONSTRAINT fk_dispute_allocations_chargeback FOREIGN KEY
        (merchant_id, chargeback_id) REFERENCES chargebacks (merchant_id, id),
    CONSTRAINT fk_dispute_allocations_intent FOREIGN KEY
        (merchant_id, payment_intent_id) REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_dispute_allocations_refund FOREIGN KEY
        (merchant_id, refund_id, payment_intent_id) REFERENCES refunds (merchant_id, id, payment_intent_id),
    CONSTRAINT ck_dispute_allocations_amount CHECK (
        captured_amount > 0 AND prior_refund_amount >= 0 AND prior_dispute_amount >= 0
        AND allocated_amount >= 0 AND allocated_amount <= captured_amount
    ),
    CONSTRAINT ck_dispute_allocations_result CHECK (
        (result = 'ALLOCATED' AND allocated_amount > 0 AND refund_id IS NULL)
        OR (result = 'OFFSET_BY_REFUND' AND allocated_amount = 0 AND refund_id IS NOT NULL)
    )
);

CREATE TABLE dispute_evidence_packets (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    chargeback_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    authorization_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_attestation VARCHAR(64) NOT NULL,
    avs_result VARCHAR(32) NOT NULL,
    cvc_result VARCHAR(32) NOT NULL,
    three_ds_authenticated BOOLEAN NOT NULL,
    delivery_confirmed BOOLEAN NOT NULL,
    customer_communication_present BOOLEAN NOT NULL,
    refund_history_present BOOLEAN NOT NULL,
    device_continuity BOOLEAN NOT NULL,
    due_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_dispute_evidence_packets PRIMARY KEY (id),
    CONSTRAINT uk_dispute_packets_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_dispute_packets_chargeback UNIQUE (merchant_id, chargeback_id),
    CONSTRAINT fk_dispute_packets_chargeback FOREIGN KEY (merchant_id, chargeback_id)
        REFERENCES chargebacks (merchant_id, id),
    CONSTRAINT fk_dispute_packets_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, id),
    CONSTRAINT fk_dispute_packets_attempt FOREIGN KEY (merchant_id, payment_attempt_id, payment_intent_id)
        REFERENCES payment_attempts (merchant_id, id, payment_intent_id),
    CONSTRAINT fk_dispute_packets_authorization FOREIGN KEY (merchant_id, authorization_id, payment_attempt_id)
        REFERENCES authorizations (merchant_id, id, payment_attempt_id),
    CONSTRAINT fk_dispute_packets_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT ck_dispute_packets_status CHECK (status = 'DRAFT'),
    CONSTRAINT ck_dispute_packets_source CHECK (source_attestation = 'SIMULATOR_MERCHANT_INGESTED'),
    CONSTRAINT ck_dispute_packets_avs CHECK (avs_result IN ('MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    CONSTRAINT ck_dispute_packets_cvc CHECK (cvc_result IN ('MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    CONSTRAINT ck_dispute_packets_due CHECK (created_at <= due_at)
);

ALTER TABLE fraud_labels ADD COLUMN source_chargeback_id UUID;
ALTER TABLE fraud_labels ALTER COLUMN source_review_resolution_id DROP NOT NULL;
ALTER TABLE fraud_labels ALTER COLUMN actor_operator_id DROP NOT NULL;
ALTER TABLE fraud_labels DROP CONSTRAINT ck_fraud_labels_semantics;
ALTER TABLE fraud_labels ADD CONSTRAINT fk_fraud_labels_chargeback FOREIGN KEY
    (merchant_id, source_chargeback_id) REFERENCES chargebacks (merchant_id, id);
ALTER TABLE fraud_labels ADD CONSTRAINT uk_fraud_labels_source_chargeback
    UNIQUE (merchant_id, source_chargeback_id);
ALTER TABLE fraud_labels ADD CONSTRAINT ck_fraud_labels_semantics CHECK (
    (source = 'MANUAL_REVIEW' AND source_review_resolution_id IS NOT NULL
        AND actor_operator_id IS NOT NULL AND source_chargeback_id IS NULL)
    OR
    (source = 'CHARGEBACK' AND source_review_resolution_id IS NULL
        AND actor_operator_id IS NULL AND source_chargeback_id IS NOT NULL
        AND label_value = 'FRAUD'
        AND fraud_type IN ('STOLEN_PAYMENT_CREDENTIAL', 'ACCOUNT_TAKEOVER', 'CARD_TESTING',
            'FIRST_PARTY_MISUSE'))
);

ALTER TABLE ledger_entries ADD COLUMN chargeback_id UUID;
ALTER TABLE ledger_entries ADD CONSTRAINT fk_ledger_entries_chargeback FOREIGN KEY
    (merchant_id, chargeback_id, payment_intent_id)
    REFERENCES chargebacks (merchant_id, id, payment_intent_id);
ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_entries_source_type;
ALTER TABLE ledger_entries ADD CONSTRAINT ck_ledger_entries_source_type
    CHECK (source_type IN ('PAYMENT_INTENT_CAPTURE', 'REFUND', 'CHARGEBACK'));
ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_entries_source_posting;
ALTER TABLE ledger_entries ADD CONSTRAINT ck_ledger_entries_source_posting CHECK (
    (source_type = 'PAYMENT_INTENT_CAPTURE' AND refund_id IS NULL AND chargeback_id IS NULL
        AND source_id = payment_intent_id
        AND ((account = 'CUSTOMER_RECEIVABLE' AND direction = 'DEBIT')
            OR (account = 'MERCHANT_PAYABLE' AND direction = 'CREDIT')))
    OR
    (source_type = 'REFUND' AND refund_id IS NOT NULL AND chargeback_id IS NULL
        AND source_id = refund_id
        AND ((account = 'MERCHANT_PAYABLE' AND direction = 'DEBIT')
            OR (account = 'CUSTOMER_RECEIVABLE' AND direction = 'CREDIT')))
    OR
    (source_type = 'CHARGEBACK' AND refund_id IS NULL AND chargeback_id IS NOT NULL
        AND source_id = chargeback_id
        AND ((account = 'MERCHANT_PAYABLE' AND direction = 'DEBIT')
            OR (account = 'CUSTOMER_RECEIVABLE' AND direction = 'CREDIT')))
);

ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_event_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_event_type CHECK (event_type IN (
    'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
    'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
    'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
    'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
    'authentication_challenge.created', 'authentication_challenge.completed',
    'chargeback.created', 'dispute_evidence_packet.created'
));
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_resource_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_resource_type CHECK (
    resource_type IN ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'MANUAL_REVIEW_CASE', 'REFUND',
        'FRAUD_LABEL', 'AUTHENTICATION_CHALLENGE', 'CHARGEBACK', 'DISPUTE_EVIDENCE_PACKET')
);
ALTER TABLE webhook_endpoint_event_subscriptions
    DROP CONSTRAINT ck_webhook_endpoint_event_subscriptions_type;
ALTER TABLE webhook_endpoint_event_subscriptions
    ADD CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (event_type IN (
        'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
        'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
        'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
        'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
        'authentication_challenge.created', 'authentication_challenge.completed',
        'chargeback.created', 'dispute_evidence_packet.created'
    ));
