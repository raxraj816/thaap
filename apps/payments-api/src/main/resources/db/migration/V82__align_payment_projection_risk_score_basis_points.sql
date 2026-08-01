ALTER TABLE payment_risk_projections
    DROP CONSTRAINT ck_payment_risk_projections_score;

ALTER TABLE payment_risk_projections
    ADD CONSTRAINT ck_payment_risk_projections_score
    CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 10000);
