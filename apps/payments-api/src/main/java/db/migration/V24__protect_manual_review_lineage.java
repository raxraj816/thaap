package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V24__protect_manual_review_lineage extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION enforce_manual_review_history()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $$
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
                               OR OLD.created_at IS DISTINCT FROM NEW.created_at
                               OR OLD.status <> 'OPEN'
                               OR NEW.status <> 'CANCELED'
                               OR NEW.cancellation_reason <> 'MERCHANT_CANCELED'
                               OR NEW.canceled_at IS NULL THEN
                                RAISE EXCEPTION 'manual review history is immutable';
                            END IF;
                            RETURN NEW;
                        END;
                        $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_manual_review_history
                        BEFORE UPDATE OR DELETE ON manual_review_cases
                        FOR EACH ROW EXECUTE FUNCTION enforce_manual_review_history()
                        """);
            } else if ("H2".equals(product)) {
                statement.execute("""
                        CREATE TRIGGER trg_manual_review_history
                        BEFORE DELETE ON manual_review_cases
                        AS 'org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                        throws java.sql.SQLException {
                                    throw new java.sql.SQLException("manual review history is immutable");
                                }
                            };
                        }'
                        """);
            } else {
                throw new IllegalStateException("Manual review lineage protection is unsupported for " + product);
            }
        }
    }
}
