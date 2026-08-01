ALTER TABLE feature_snapshots
    ADD CONSTRAINT uk_feature_snapshots_exact_decision_lineage
    UNIQUE (merchant_id, id, fraud_decision_id);

CREATE TABLE domain_event_source_lineage (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    domain_event_id UUID NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    fraud_decision_id UUID NOT NULL,
    feature_snapshot_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_domain_event_source_lineage PRIMARY KEY (id),
    CONSTRAINT uk_domain_event_source_lineage_event
        UNIQUE (merchant_id, domain_event_id),
    CONSTRAINT fk_domain_event_source_lineage_event
        FOREIGN KEY (merchant_id, domain_event_id)
        REFERENCES domain_events (merchant_id, id),
    CONSTRAINT fk_domain_event_source_lineage_decision
        FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT fk_domain_event_source_lineage_snapshot
        FOREIGN KEY (merchant_id, feature_snapshot_id, fraud_decision_id)
        REFERENCES feature_snapshots (merchant_id, id, fraud_decision_id),
    CONSTRAINT ck_domain_event_source_lineage_type
        CHECK (source_type = 'FRAUD_DECISION')
);

CREATE INDEX idx_domain_event_source_lineage_decision
    ON domain_event_source_lineage (merchant_id, fraud_decision_id, domain_event_id);

ALTER TABLE payment_risk_projections
    ADD COLUMN fraud_decision_id UUID;

ALTER TABLE payment_risk_projections
    ADD COLUMN feature_snapshot_id UUID;

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT fk_payment_risk_projections_decision
    FOREIGN KEY (merchant_id, fraud_decision_id)
    REFERENCES fraud_decisions (merchant_id, id);

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT fk_payment_risk_projections_snapshot
    FOREIGN KEY (merchant_id, feature_snapshot_id, fraud_decision_id)
    REFERENCES feature_snapshots (merchant_id, id, fraud_decision_id);

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projections_exact_decision_lineage CHECK (
        (fraud_decision_id IS NULL AND feature_snapshot_id IS NULL)
        OR (fraud_decision_id IS NOT NULL AND feature_snapshot_id IS NOT NULL));
