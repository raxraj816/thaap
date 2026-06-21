CREATE TABLE audit_logs (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    action VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(255),
    reason VARCHAR(500),
    before_json TEXT,
    after_json TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE INDEX idx_audit_logs_merchant ON audit_logs (merchant_id);
CREATE INDEX idx_audit_logs_merchant_created ON audit_logs (merchant_id, created_at, id);
CREATE INDEX idx_audit_logs_merchant_action_created ON audit_logs (merchant_id, action, created_at, id);
CREATE INDEX idx_audit_logs_merchant_aggregate ON audit_logs (merchant_id, aggregate_type, aggregate_id, created_at, id);
CREATE INDEX idx_audit_logs_request_id ON audit_logs (request_id);
