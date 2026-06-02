CREATE TABLE merchants (
    id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    api_key_prefix VARCHAR(32) NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchants PRIMARY KEY (id),
    CONSTRAINT uk_merchants_api_key_hash UNIQUE (api_key_hash)
);

CREATE TABLE customers (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    email_hash VARCHAR(255) NOT NULL,
    phone_hash VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uk_customers_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_customers_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE TABLE payment_methods (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    type VARCHAR(255) NOT NULL,
    token VARCHAR(255) NOT NULL,
    card_fingerprint VARCHAR(255) NOT NULL,
    card_bin VARCHAR(6) NOT NULL,
    card_last4 VARCHAR(4) NOT NULL,
    card_funding VARCHAR(32) NOT NULL,
    card_country VARCHAR(2) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_methods PRIMARY KEY (id),
    CONSTRAINT uk_payment_methods_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_payment_methods_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_payment_methods_customer FOREIGN KEY (merchant_id, customer_id) REFERENCES customers (merchant_id, id)
);

CREATE TABLE payment_intents (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    capture_method VARCHAR(32) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_intents PRIMARY KEY (id),
    CONSTRAINT uk_payment_intents_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT fk_payment_intents_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_payment_intents_customer FOREIGN KEY (merchant_id, customer_id) REFERENCES customers (merchant_id, id)
);

CREATE TABLE idempotency_records (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    http_status INTEGER NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_merchant_key UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT fk_idempotency_records_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE TABLE webhook_endpoints (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    url VARCHAR(2048) NOT NULL,
    active BOOLEAN NOT NULL,
    signing_secret_prefix VARCHAR(32) NOT NULL,
    signing_secret_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_endpoints PRIMARY KEY (id),
    CONSTRAINT uk_webhook_endpoints_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_webhook_endpoints_signing_secret_hash UNIQUE (signing_secret_hash),
    CONSTRAINT fk_webhook_endpoints_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE TABLE webhook_deliveries (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    webhook_endpoint_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    resource_id UUID NOT NULL,
    status VARCHAR(255) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    last_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    delivered_at TIMESTAMP(6) WITH TIME ZONE,
    last_error TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_webhook_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_webhook_deliveries_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_webhook_deliveries_endpoint FOREIGN KEY (merchant_id, webhook_endpoint_id) REFERENCES webhook_endpoints (merchant_id, id)
);

CREATE TABLE fraud_decisions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    action VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_decisions PRIMARY KEY (id),
    CONSTRAINT fk_fraud_decisions_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE TABLE fraud_rules (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_rules PRIMARY KEY (id),
    CONSTRAINT fk_fraud_rules_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE TABLE review_cases (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_cases PRIMARY KEY (id),
    CONSTRAINT fk_review_cases_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE INDEX idx_customers_merchant ON customers (merchant_id);
CREATE INDEX idx_payment_methods_merchant ON payment_methods (merchant_id);
CREATE INDEX idx_payment_methods_customer ON payment_methods (customer_id);
CREATE INDEX idx_payment_methods_merchant_customer ON payment_methods (merchant_id, customer_id);
CREATE INDEX idx_payment_methods_merchant_bin ON payment_methods (merchant_id, card_bin);
CREATE INDEX idx_payment_intents_merchant ON payment_intents (merchant_id);
CREATE INDEX idx_payment_intents_customer ON payment_intents (customer_id);
CREATE INDEX idx_payment_intents_merchant_customer ON payment_intents (merchant_id, customer_id);
CREATE INDEX idx_idempotency_merchant ON idempotency_records (merchant_id);
CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints (merchant_id);
CREATE INDEX idx_webhook_endpoints_merchant_active ON webhook_endpoints (merchant_id, active);
CREATE INDEX idx_webhook_deliveries_merchant ON webhook_deliveries (merchant_id);
CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries (webhook_endpoint_id);
CREATE INDEX idx_webhook_deliveries_merchant_created ON webhook_deliveries (merchant_id, created_at, id);
CREATE INDEX idx_webhook_deliveries_merchant_status_next_attempt ON webhook_deliveries (merchant_id, status, next_attempt_at, id);
CREATE INDEX idx_fraud_decisions_merchant ON fraud_decisions (merchant_id);
CREATE INDEX idx_fraud_decisions_merchant_created ON fraud_decisions (merchant_id, created_at, id);
CREATE INDEX idx_fraud_rules_merchant ON fraud_rules (merchant_id);
CREATE INDEX idx_fraud_rules_merchant_created ON fraud_rules (merchant_id, created_at, id);
CREATE INDEX idx_review_cases_merchant ON review_cases (merchant_id);
CREATE INDEX idx_review_cases_merchant_created ON review_cases (merchant_id, created_at, id);
