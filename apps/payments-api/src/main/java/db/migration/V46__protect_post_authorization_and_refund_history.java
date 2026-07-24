package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V46__protect_post_authorization_and_refund_history extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_post_authorization_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Post-authorization history is append-only';
                    END; $$
                    """);
            for (String table : new String[]{"post_authorization_signals", "review_refund_actions"}) {
                statement.execute("CREATE TRIGGER trg_" + table + "_append_only BEFORE UPDATE OR DELETE ON "
                        + table + " FOR EACH ROW EXECUTE FUNCTION reject_post_authorization_history_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION enforce_refund_history()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'refund history is append-only';
                    END; $$
                    """);
            statement.execute("CREATE TRIGGER trg_refunds_append_only BEFORE UPDATE OR DELETE ON refunds "
                    + "FOR EACH ROW EXECUTE FUNCTION enforce_refund_history()");
            statement.execute("""
                    CREATE FUNCTION validate_post_authorization_signal_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE intent_row payment_intents%ROWTYPE;
                    DECLARE attempt_row payment_attempts%ROWTYPE;
                    DECLARE authorization_row authorizations%ROWTYPE;
                    DECLARE decision_row fraud_decisions%ROWTYPE;
                    BEGIN
                        SELECT * INTO intent_row FROM payment_intents
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.payment_intent_id FOR UPDATE;
                        SELECT * INTO attempt_row FROM payment_attempts
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.payment_attempt_id;
                        SELECT * INTO authorization_row FROM authorizations
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.authorization_id;
                        SELECT * INTO decision_row FROM fraud_decisions
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.fraud_decision_id;
                        IF intent_row.id IS NULL OR attempt_row.id IS NULL
                           OR authorization_row.id IS NULL OR decision_row.id IS NULL
                           OR intent_row.status NOT IN ('CAPTURED', 'REFUNDED')
                           OR intent_row.capture_method <> 'AUTOMATIC'
                           OR attempt_row.payment_intent_id <> NEW.payment_intent_id
                           OR attempt_row.status <> 'AUTHORIZED'
                           OR authorization_row.payment_attempt_id <> NEW.payment_attempt_id
                           OR authorization_row.status <> 'APPROVED'
                           OR NEW.evidence_occurred_at < authorization_row.created_at
                           OR authorization_row.authorized_amount <> intent_row.amount
                           OR authorization_row.currency <> intent_row.currency
                           OR decision_row.payment_attempt_id <> NEW.payment_attempt_id
                           OR decision_row.traffic_origin <> NEW.traffic_origin THEN
                            RAISE EXCEPTION 'post-authorization signal lineage mismatch';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_post_authorization_signal_lineage
                    BEFORE INSERT ON post_authorization_signals
                    FOR EACH ROW EXECUTE FUNCTION validate_post_authorization_signal_lineage()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION enforce_manual_review_history()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'manual review history is immutable';
                        END IF;
                        IF OLD.id IS DISTINCT FROM NEW.id
                           OR OLD.merchant_id IS DISTINCT FROM NEW.merchant_id
                           OR OLD.payment_intent_id IS DISTINCT FROM NEW.payment_intent_id
                           OR OLD.payment_attempt_id IS DISTINCT FROM NEW.payment_attempt_id
                           OR OLD.fraud_decision_id IS DISTINCT FROM NEW.fraud_decision_id
                           OR OLD.decision_action IS DISTINCT FROM NEW.decision_action
                           OR OLD.reason_codes IS DISTINCT FROM NEW.reason_codes
                           OR OLD.traffic_origin IS DISTINCT FROM NEW.traffic_origin
                           OR OLD.review_stage IS DISTINCT FROM NEW.review_stage
                           OR OLD.source_post_authorization_signal_id IS DISTINCT FROM
                                NEW.source_post_authorization_signal_id
                           OR OLD.created_at IS DISTINCT FROM NEW.created_at
                           OR NEW.assignment_version < OLD.assignment_version
                           OR NOT ((OLD.status = 'OPEN' AND NEW.status IN ('ASSIGNED', 'RESOLVED', 'CANCELED'))
                                OR (OLD.status = 'ASSIGNED'
                                    AND NEW.status IN ('OPEN', 'ASSIGNED', 'RESOLVED', 'CANCELED'))) THEN
                            RAISE EXCEPTION 'manual review history is immutable';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_review_refund_action_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE resolution_row review_resolutions%ROWTYPE;
                    DECLARE refund_row refunds%ROWTYPE;
                    BEGIN
                        SELECT * INTO resolution_row FROM review_resolutions
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.review_resolution_id;
                        SELECT * INTO refund_row FROM refunds
                        WHERE merchant_id = NEW.merchant_id AND id = NEW.refund_id;
                        IF resolution_row.id IS NULL OR refund_row.id IS NULL
                           OR resolution_row.review_stage <> 'POST_AUTHORIZATION'
                           OR resolution_row.payment_intent_id <> NEW.payment_intent_id
                           OR refund_row.payment_intent_id <> NEW.payment_intent_id
                           OR NOT ((resolution_row.outcome = 'REFUND'
                                    AND NEW.reason_code = 'RISK_REVIEW_REMEDIATION')
                               OR (resolution_row.outcome = 'REFUND_AND_REPORT_FRAUD'
                                    AND NEW.reason_code = 'CONFIRMED_FRAUD_CUSTOMER_PROTECTION'))
                           OR (NEW.action_result = 'CREATED'
                               AND refund_row.source_review_resolution_id <> NEW.review_resolution_id)
                           OR (NEW.action_result = 'REUSED'
                               AND refund_row.source_review_resolution_id IS NOT NULL) THEN
                            RAISE EXCEPTION 'review refund action lineage mismatch';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_review_refund_action_lineage
                    BEFORE INSERT ON review_refund_actions
                    FOR EACH ROW EXECUTE FUNCTION validate_review_refund_action_lineage()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_refund_reason_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE resolution_row review_resolutions%ROWTYPE;
                    BEGIN
                        IF NEW.source_review_resolution_id IS NOT NULL THEN
                            SELECT * INTO resolution_row FROM review_resolutions
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.source_review_resolution_id;
                            IF resolution_row.id IS NULL
                               OR resolution_row.review_stage <> 'POST_AUTHORIZATION'
                               OR resolution_row.payment_intent_id <> NEW.payment_intent_id
                               OR NOT ((resolution_row.outcome = 'REFUND'
                                        AND NEW.reason_code = 'RISK_REVIEW_REMEDIATION')
                                   OR (resolution_row.outcome = 'REFUND_AND_REPORT_FRAUD'
                                        AND NEW.reason_code = 'CONFIRMED_FRAUD_CUSTOMER_PROTECTION')) THEN
                                RAISE EXCEPTION 'refund reason lineage mismatch';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_refund_reason_lineage
                    BEFORE INSERT ON refunds
                    FOR EACH ROW EXECUTE FUNCTION validate_refund_reason_lineage()
                    """);
            statement.execute("DROP TRIGGER trg_fraud_label_lineage ON fraud_labels");
            statement.execute("DROP FUNCTION validate_fraud_label_lineage()");
            statement.execute("""
                    CREATE FUNCTION validate_fraud_label_lineage()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE resolution_row review_resolutions%ROWTYPE;
                    DECLARE review_row manual_review_cases%ROWTYPE;
                    DECLARE signal_row post_authorization_signals%ROWTYPE;
                    DECLARE chargeback_row chargebacks%ROWTYPE;
                    DECLARE prior_row fraud_labels%ROWTYPE;
                    BEGIN
                        IF NEW.source IN ('MANUAL_REVIEW', 'POST_AUTHORIZATION_SIGNAL') THEN
                            SELECT * INTO resolution_row FROM review_resolutions
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.source_review_resolution_id;
                            SELECT * INTO review_row FROM manual_review_cases
                            WHERE merchant_id = NEW.merchant_id AND id = resolution_row.manual_review_case_id;
                            IF resolution_row.id IS NULL OR review_row.id IS NULL
                               OR resolution_row.payment_intent_id <> NEW.payment_intent_id
                               OR resolution_row.payment_attempt_id <> NEW.payment_attempt_id
                               OR resolution_row.fraud_decision_id <> NEW.fraud_decision_id
                               OR review_row.traffic_origin <> NEW.traffic_origin
                               OR resolution_row.classification NOT IN
                                    ('CONFIRMED_FRAUD', 'CONFIRMED_LEGITIMATE')
                               OR resolution_row.fraud_type IS DISTINCT FROM NEW.fraud_type
                               OR resolution_row.reason_code <> NEW.reason_code THEN
                                RAISE EXCEPTION 'fraud label review source lineage mismatch';
                            END IF;
                            IF NEW.source = 'MANUAL_REVIEW' AND review_row.review_stage <> 'PRE_AUTHORIZATION' THEN
                                RAISE EXCEPTION 'fraud label review stage mismatch';
                            END IF;
                            IF NEW.source = 'POST_AUTHORIZATION_SIGNAL' THEN
                                SELECT * INTO signal_row FROM post_authorization_signals
                                WHERE merchant_id = NEW.merchant_id
                                  AND id = NEW.source_post_authorization_signal_id;
                                IF review_row.review_stage <> 'POST_AUTHORIZATION'
                                   OR signal_row.id IS NULL
                                   OR review_row.source_post_authorization_signal_id <> signal_row.id
                                   OR signal_row.payment_intent_id <> NEW.payment_intent_id
                                   OR signal_row.evidence_occurred_at <> NEW.evidence_occurred_at
                                   OR NEW.available_at < signal_row.available_at THEN
                                    RAISE EXCEPTION 'fraud label post-authorization source mismatch';
                                END IF;
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
                                   WHEN 'FRAUDULENT_STOLEN_CREDENTIAL' THEN 'STOLEN_PAYMENT_CREDENTIAL'
                                   WHEN 'FRAUDULENT_ACCOUNT_TAKEOVER' THEN 'ACCOUNT_TAKEOVER'
                                   WHEN 'FRAUDULENT_CARD_TESTING' THEN 'CARD_TESTING'
                                   WHEN 'FRAUDULENT_FIRST_PARTY_MISUSE' THEN 'FIRST_PARTY_MISUSE'
                                   ELSE NULL END) THEN
                                RAISE EXCEPTION 'fraud label chargeback source lineage mismatch';
                            END IF;
                        ELSE
                            RAISE EXCEPTION 'unsupported fraud label source';
                        END IF;
                        IF NEW.supersedes_label_id IS NOT NULL THEN
                            SELECT * INTO prior_row FROM fraud_labels
                            WHERE merchant_id = NEW.merchant_id AND id = NEW.supersedes_label_id;
                            IF prior_row.id IS NULL
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
            statement.execute("CREATE TRIGGER trg_fraud_label_lineage BEFORE INSERT ON fraud_labels "
                    + "FOR EACH ROW EXECUTE FUNCTION validate_fraud_label_lineage()");
        }
    }
}
