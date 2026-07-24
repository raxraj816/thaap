package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V29__add_review_approval_and_legitimate_labels extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_outcome");
            statement.execute("ALTER TABLE review_resolutions DROP CONSTRAINT ck_review_resolutions_semantics");
            statement.execute("ALTER TABLE fraud_labels DROP CONSTRAINT ck_fraud_labels_semantics");
            statement.execute("""
                    ALTER TABLE review_resolutions
                    ADD CONSTRAINT ck_review_resolutions_outcome CHECK (
                        outcome IN ('APPROVE', 'CANCEL', 'CANCEL_AND_REPORT_FRAUD')
                    )
                    """);
            statement.execute("""
                    ALTER TABLE review_resolutions
                    ADD CONSTRAINT ck_review_resolutions_semantics CHECK (
                        (outcome = 'APPROVE'
                            AND classification IN ('INCONCLUSIVE', 'CONFIRMED_LEGITIMATE')
                            AND fraud_type IS NULL
                            AND reason_code = 'MANUAL_REVIEW_APPROVED')
                        OR
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
                    )
                    """);
            statement.execute("""
                    ALTER TABLE fraud_labels
                    ADD CONSTRAINT ck_fraud_labels_semantics CHECK (
                        source = 'MANUAL_REVIEW'
                        AND (
                            (label_value = 'FRAUD'
                                AND fraud_type IN ('STOLEN_PAYMENT_CREDENTIAL', 'ACCOUNT_TAKEOVER',
                                    'CARD_TESTING', 'FIRST_PARTY_MISUSE', 'OTHER_CONFIRMED_FRAUD'))
                            OR (label_value = 'LEGITIMATE' AND fraud_type IS NULL)
                        )
                    )
                    """);
            if ("PostgreSQL".equals(product)) {
                replaceLabelLineageFunction(statement);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("Review approval lineage protection is unsupported for " + product);
            }
        }
    }

    private void replaceLabelLineageFunction(Statement statement) throws Exception {
        statement.execute("""
                CREATE OR REPLACE FUNCTION validate_fraud_label_lineage()
                RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE resolution_row review_resolutions%ROWTYPE;
                DECLARE prior_row fraud_labels%ROWTYPE;
                BEGIN
                    SELECT * INTO resolution_row FROM review_resolutions
                    WHERE merchant_id = NEW.merchant_id AND id = NEW.source_review_resolution_id;
                    IF NOT FOUND
                       OR resolution_row.payment_intent_id <> NEW.payment_intent_id
                       OR resolution_row.payment_attempt_id <> NEW.payment_attempt_id
                       OR resolution_row.fraud_decision_id <> NEW.fraud_decision_id
                       OR (SELECT traffic_origin FROM manual_review_cases
                           WHERE merchant_id = NEW.merchant_id
                             AND id = resolution_row.manual_review_case_id) <> NEW.traffic_origin
                       OR NOT (
                            (NEW.label_value = 'FRAUD'
                                AND resolution_row.outcome = 'CANCEL_AND_REPORT_FRAUD'
                                AND resolution_row.classification = 'CONFIRMED_FRAUD'
                                AND resolution_row.fraud_type = NEW.fraud_type
                                AND resolution_row.reason_code = NEW.reason_code)
                            OR
                            (NEW.label_value = 'LEGITIMATE'
                                AND resolution_row.outcome = 'APPROVE'
                                AND resolution_row.classification = 'CONFIRMED_LEGITIMATE'
                                AND resolution_row.fraud_type IS NULL
                                AND NEW.fraud_type IS NULL
                                AND resolution_row.reason_code = NEW.reason_code)
                       ) THEN
                        RAISE EXCEPTION 'fraud label source lineage mismatch';
                    END IF;
                    IF NEW.supersedes_label_id IS NOT NULL THEN
                        SELECT * INTO prior_row FROM fraud_labels
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.supersedes_label_id;
                        IF NOT FOUND
                           OR prior_row.payment_intent_id <> NEW.payment_intent_id
                           OR prior_row.payment_attempt_id <> NEW.payment_attempt_id
                           OR prior_row.fraud_decision_id <> NEW.fraud_decision_id
                           OR NEW.supersedes_label_id = NEW.id THEN
                            RAISE EXCEPTION 'fraud label supersession lineage mismatch';
                        END IF;
                    END IF;
                    RETURN NEW;
                END; $$
                """);
    }
}
