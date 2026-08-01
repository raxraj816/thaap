package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Binds each fraud event lineage row to the decision's exact payment stream. */
public final class V85__bind_fraud_event_lineage_to_payment_stream extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION validate_domain_event_source_lineage()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                              FROM domain_events event
                              JOIN fraud_decisions decision
                                ON decision.merchant_id = event.merchant_id
                               AND decision.id = NEW.fraud_decision_id
                              JOIN payment_attempts attempt
                                ON attempt.merchant_id = decision.merchant_id
                               AND attempt.id = decision.payment_attempt_id
                               AND attempt.payment_intent_id = event.stream_id
                              JOIN feature_snapshots snapshot
                                ON snapshot.merchant_id = decision.merchant_id
                               AND snapshot.id = NEW.feature_snapshot_id
                               AND snapshot.fraud_decision_id = decision.id
                               AND snapshot.payment_attempt_id = attempt.id
                             WHERE event.merchant_id = NEW.merchant_id
                               AND event.id = NEW.domain_event_id
                               AND event.stream_type = 'PAYMENT'
                               AND event.event_type = 'fraud_decision.created') THEN
                            RAISE EXCEPTION 'event source lineage requires the exact fraud decision payment chain'
                                USING ERRCODE = '23514';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
        }
    }
}
