ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_classification;
ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_reason;

ALTER TABLE review_resolutions
    ADD CONSTRAINT ck_review_resolutions_semantics CHECK (
        (outcome = 'CANCEL'
            AND classification = 'INCONCLUSIVE'
            AND fraud_type IS NULL
            AND reason_code = 'RISK_TOO_HIGH')
        OR
        (outcome = 'CANCEL_AND_REPORT_FRAUD'
            AND classification = 'CONFIRMED_FRAUD'
            AND (
                (fraud_type = 'STOLEN_PAYMENT_CREDENTIAL'
                    AND reason_code = 'IDENTITY_MISMATCH_CONFIRMED')
                OR (fraud_type = 'ACCOUNT_TAKEOVER'
                    AND reason_code = 'ACCOUNT_TAKEOVER_CONFIRMED')
                OR (fraud_type = 'CARD_TESTING'
                    AND reason_code = 'CARD_TESTING_CONFIRMED')
                OR (fraud_type = 'FIRST_PARTY_MISUSE'
                    AND reason_code = 'FIRST_PARTY_MISUSE_CONFIRMED')
                OR (fraud_type = 'OTHER_CONFIRMED_FRAUD'
                    AND reason_code = 'OTHER_FRAUD_CONFIRMED')
            ))
    );
