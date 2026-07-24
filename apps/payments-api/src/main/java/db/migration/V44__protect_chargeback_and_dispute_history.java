package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V44__protect_chargeback_and_dispute_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_chargeback_and_dispute_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Chargeback and dispute history is append-only';
                    END; $$
                    """);
            for (String table : new String[]{"chargebacks", "dispute_offset_allocations",
                    "dispute_evidence_packets"}) {
                statement.execute("CREATE TRIGGER trg_" + table + "_append_only BEFORE UPDATE OR DELETE ON "
                        + table + " FOR EACH ROW EXECUTE FUNCTION reject_chargeback_and_dispute_history_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION validate_chargeback_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE intent_row payment_intents%ROWTYPE;
                    DECLARE attempt_row payment_attempts%ROWTYPE;
                    DECLARE authorization_row authorizations%ROWTYPE;
                    DECLARE decision_row fraud_decisions%ROWTYPE;
                    DECLARE existing_allocated_count BIGINT;
                    BEGIN
                        SELECT * INTO intent_row FROM payment_intents
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.payment_intent_id
                        FOR UPDATE;
                        SELECT * INTO attempt_row FROM payment_attempts
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.payment_attempt_id;
                        SELECT * INTO authorization_row FROM authorizations
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.authorization_id;
                        SELECT * INTO decision_row FROM fraud_decisions
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.fraud_decision_id;
                        SELECT COUNT(*) INTO existing_allocated_count FROM chargebacks
                        WHERE merchant_id = NEW.merchant_id
                          AND payment_intent_id = NEW.payment_intent_id
                          AND status = 'ALLOCATED';
                        IF intent_row.id IS NULL
                           OR attempt_row.id IS NULL
                           OR authorization_row.id IS NULL
                           OR decision_row.id IS NULL
                           OR intent_row.status NOT IN ('CAPTURED', 'REFUNDED')
                           OR intent_row.capture_method <> 'AUTOMATIC'
                           OR intent_row.amount < NEW.amount
                           OR intent_row.currency <> NEW.currency
                           OR attempt_row.payment_intent_id <> NEW.payment_intent_id
                           OR attempt_row.status <> 'AUTHORIZED'
                           OR authorization_row.payment_attempt_id <> NEW.payment_attempt_id
                           OR authorization_row.status <> 'APPROVED'
                           OR authorization_row.authorized_amount <> intent_row.amount
                           OR authorization_row.currency <> NEW.currency
                           OR decision_row.payment_attempt_id <> NEW.payment_attempt_id
                           OR decision_row.traffic_origin <> NEW.traffic_origin
                           OR (intent_row.status = 'CAPTURED'
                               AND (NEW.status <> 'ALLOCATED' OR NEW.allocated_amount <= 0))
                           OR (intent_row.status = 'REFUNDED'
                               AND (NEW.status <> 'OFFSET_BY_REFUND' OR NEW.allocated_amount <> 0))
                           OR (NEW.status = 'ALLOCATED' AND existing_allocated_count <> 0) THEN
                            RAISE EXCEPTION 'chargeback payment lineage mismatch';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_chargeback_lineage
                    BEFORE INSERT ON chargebacks
                    FOR EACH ROW EXECUTE FUNCTION validate_chargeback_lineage()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_dispute_allocation_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE chargeback_row chargebacks%ROWTYPE;
                    DECLARE refund_row refunds%ROWTYPE;
                    DECLARE intent_row payment_intents%ROWTYPE;
                    BEGIN
                        SELECT * INTO chargeback_row FROM chargebacks
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.chargeback_id;
                        SELECT * INTO intent_row FROM payment_intents
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.payment_intent_id;
                        IF chargeback_row.id IS NULL
                           OR intent_row.id IS NULL
                           OR chargeback_row.payment_intent_id <> NEW.payment_intent_id
                           OR chargeback_row.currency <> NEW.currency
                           OR chargeback_row.allocated_amount <> NEW.allocated_amount
                           OR chargeback_row.status <> NEW.result
                           OR intent_row.amount <> NEW.captured_amount
                           OR intent_row.currency <> NEW.currency
                           OR NEW.captured_amount < NEW.prior_refund_amount + NEW.prior_dispute_amount
                              + NEW.allocated_amount THEN
                            RAISE EXCEPTION 'dispute allocation lineage mismatch';
                        END IF;
                        IF NEW.refund_id IS NOT NULL THEN
                            SELECT * INTO refund_row FROM refunds
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.refund_id;
                            IF refund_row.id IS NULL OR refund_row.payment_intent_id <> NEW.payment_intent_id
                               OR refund_row.amount <> NEW.prior_refund_amount THEN
                                RAISE EXCEPTION 'dispute refund offset mismatch';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_dispute_allocation_lineage
                    BEFORE INSERT ON dispute_offset_allocations
                    FOR EACH ROW EXECUTE FUNCTION validate_dispute_allocation_lineage()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_dispute_evidence_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE chargeback_row chargebacks%ROWTYPE;
                    DECLARE authorization_row authorizations%ROWTYPE;
                    DECLARE expected_avs VARCHAR(32);
                    DECLARE expected_cvc VARCHAR(32);
                    BEGIN
                        SELECT * INTO chargeback_row FROM chargebacks
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.chargeback_id;
                        SELECT * INTO authorization_row FROM authorizations
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.authorization_id;
                        expected_avs := CASE authorization_row.avs_postal_code_check
                            WHEN 'PASS' THEN 'MATCH' WHEN 'FAIL' THEN 'NO_MATCH' ELSE 'UNAVAILABLE' END;
                        expected_cvc := CASE authorization_row.cvc_check
                            WHEN 'PASS' THEN 'MATCH' WHEN 'FAIL' THEN 'NO_MATCH' ELSE 'UNAVAILABLE' END;
                        IF chargeback_row.id IS NULL
                           OR authorization_row.id IS NULL
                           OR chargeback_row.payment_intent_id <> NEW.payment_intent_id
                           OR chargeback_row.payment_attempt_id <> NEW.payment_attempt_id
                           OR chargeback_row.authorization_id <> NEW.authorization_id
                           OR chargeback_row.fraud_decision_id <> NEW.fraud_decision_id
                           OR chargeback_row.evidence_due_at <> NEW.due_at
                           OR NEW.avs_result <> expected_avs
                           OR NEW.cvc_result <> expected_cvc
                           OR NEW.three_ds_authenticated <>
                              (authorization_row.three_ds_result = 'AUTHENTICATED') THEN
                            RAISE EXCEPTION 'dispute evidence lineage mismatch';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_dispute_evidence_lineage
                    BEFORE INSERT ON dispute_evidence_packets
                    FOR EACH ROW EXECUTE FUNCTION validate_dispute_evidence_lineage()
                    """);
            statement.execute("DROP TRIGGER trg_fraud_label_lineage ON fraud_labels");
            statement.execute("DROP FUNCTION validate_fraud_label_lineage()");
            statement.execute("""
                    CREATE FUNCTION validate_fraud_label_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE resolution_row review_resolutions%ROWTYPE;
                    DECLARE chargeback_row chargebacks%ROWTYPE;
                    DECLARE prior_row fraud_labels%ROWTYPE;
                    BEGIN
                        IF NEW.source = 'MANUAL_REVIEW' THEN
                            SELECT * INTO resolution_row FROM review_resolutions
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.source_review_resolution_id;
                            IF NOT FOUND
                               OR resolution_row.payment_intent_id <> NEW.payment_intent_id
                               OR resolution_row.payment_attempt_id <> NEW.payment_attempt_id
                               OR resolution_row.fraud_decision_id <> NEW.fraud_decision_id
                               OR (SELECT traffic_origin FROM manual_review_cases
                                   WHERE merchant_id = NEW.merchant_id
                                     AND id = resolution_row.manual_review_case_id) <> NEW.traffic_origin
                               OR resolution_row.classification NOT IN ('CONFIRMED_FRAUD', 'CONFIRMED_LEGITIMATE')
                               OR resolution_row.fraud_type IS DISTINCT FROM NEW.fraud_type
                               OR resolution_row.reason_code <> NEW.reason_code THEN
                                RAISE EXCEPTION 'fraud label review source lineage mismatch';
                            END IF;
                        ELSIF NEW.source = 'CHARGEBACK' THEN
                            SELECT * INTO chargeback_row FROM chargebacks
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.source_chargeback_id;
                            IF chargeback_row.id IS NULL
                               OR chargeback_row.payment_intent_id <> NEW.payment_intent_id
                               OR chargeback_row.payment_attempt_id <> NEW.payment_attempt_id
                               OR chargeback_row.fraud_decision_id <> NEW.fraud_decision_id
                               OR chargeback_row.traffic_origin <> NEW.traffic_origin
                               OR chargeback_row.evidence_occurred_at <> NEW.evidence_occurred_at
                               OR chargeback_row.available_at <> NEW.available_at
                               OR NEW.label_value <> 'FRAUD'
                               OR NEW.reason_code <> 'CHARGEBACK_FRAUD_CONFIRMED'
                               OR NEW.fraud_type IS DISTINCT FROM (CASE chargeback_row.reason_code
                                   WHEN 'FRAUDULENT_STOLEN_CREDENTIAL'
                                       THEN 'STOLEN_PAYMENT_CREDENTIAL'
                                   WHEN 'FRAUDULENT_ACCOUNT_TAKEOVER' THEN 'ACCOUNT_TAKEOVER'
                                   WHEN 'FRAUDULENT_CARD_TESTING' THEN 'CARD_TESTING'
                                   WHEN 'FRAUDULENT_FIRST_PARTY_MISUSE' THEN 'FIRST_PARTY_MISUSE'
                                   ELSE NULL
                               END) THEN
                                RAISE EXCEPTION 'fraud label chargeback source lineage mismatch';
                            END IF;
                        ELSE
                            RAISE EXCEPTION 'unsupported fraud label source';
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
            statement.execute("""
                    CREATE TRIGGER trg_fraud_label_lineage
                    BEFORE INSERT ON fraud_labels
                    FOR EACH ROW EXECUTE FUNCTION validate_fraud_label_lineage()
                    """);
        }
    }
}
