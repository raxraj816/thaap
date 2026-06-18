CREATE TABLE authorizations (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    processor VARCHAR(64) NOT NULL,
    processor_reference_id VARCHAR(255) NOT NULL,
    processor_response_code VARCHAR(64) NOT NULL,
    network_status VARCHAR(64) NOT NULL,
    requested_amount BIGINT NOT NULL,
    authorized_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(64) NOT NULL,
    cvc_check VARCHAR(64) NOT NULL,
    avs_line1_check VARCHAR(64) NOT NULL,
    avs_postal_code_check VARCHAR(64) NOT NULL,
    three_ds_result VARCHAR(64) NOT NULL,
    simulator_outcome_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_authorizations PRIMARY KEY (id),
    CONSTRAINT uk_authorizations_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_authorizations_attempt UNIQUE (merchant_id, payment_attempt_id),
    CONSTRAINT uk_authorizations_processor_reference UNIQUE (processor, processor_reference_id),
    CONSTRAINT fk_authorizations_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_authorizations_attempt FOREIGN KEY (merchant_id, payment_attempt_id) REFERENCES payment_attempts (merchant_id, id)
);

CREATE INDEX idx_authorizations_merchant ON authorizations (merchant_id);
CREATE INDEX idx_authorizations_attempt ON authorizations (payment_attempt_id);
CREATE INDEX idx_authorizations_merchant_created ON authorizations (merchant_id, created_at, id);
