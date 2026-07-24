package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V40__serialize_fraud_list_version_activation extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE OR REPLACE FUNCTION validate_fraud_list_lifecycle_event()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        DECLARE version_start TIMESTAMP(6) WITH TIME ZONE;
                        BEGIN
                            PERFORM 1 FROM fraud_lists
                             WHERE merchant_id = NEW.merchant_id AND id = NEW.fraud_list_id
                             FOR UPDATE;
                            IF NOT FOUND THEN
                                RAISE EXCEPTION 'fraud-list lifecycle list is missing';
                            END IF;
                            SELECT effective_from INTO version_start
                              FROM fraud_list_versions
                             WHERE merchant_id = NEW.merchant_id
                               AND id = NEW.fraud_list_version_id
                               AND fraud_list_id = NEW.fraud_list_id;
                            IF NOT FOUND OR NEW.occurred_at < version_start THEN
                                RAISE EXCEPTION 'fraud-list lifecycle time precedes its version';
                            END IF;
                            IF NEW.event_type IN ('VERSION_ACTIVATED', 'VERSION_DEACTIVATED')
                               AND NEW.lifecycle_target_id IS DISTINCT FROM NEW.fraud_list_version_id THEN
                                RAISE EXCEPTION 'fraud-list version lifecycle target mismatch';
                            END IF;
                            IF NEW.event_type = 'ENTRY_DEACTIVATED'
                               AND NEW.lifecycle_target_id IS DISTINCT FROM NEW.fraud_list_entry_id THEN
                                RAISE EXCEPTION 'fraud-list entry lifecycle target mismatch';
                            END IF;
                            IF NEW.event_type IN ('VERSION_DEACTIVATED', 'ENTRY_DEACTIVATED')
                               AND NOT EXISTS (
                                   SELECT 1 FROM fraud_list_lifecycle_events activation
                                    WHERE activation.merchant_id = NEW.merchant_id
                                      AND activation.fraud_list_version_id = NEW.fraud_list_version_id
                                      AND activation.event_type = 'VERSION_ACTIVATED'
                                      AND activation.occurred_at <= NEW.occurred_at
                               ) THEN
                                RAISE EXCEPTION 'fraud-list deactivation requires prior activation';
                            END IF;
                            IF NEW.event_type = 'VERSION_ACTIVATED'
                               AND EXISTS (
                                   SELECT 1
                                     FROM fraud_list_versions other_version
                                     JOIN fraud_list_lifecycle_events other_activation
                                       ON other_activation.merchant_id = other_version.merchant_id
                                      AND other_activation.fraud_list_version_id = other_version.id
                                      AND other_activation.event_type = 'VERSION_ACTIVATED'
                                      AND other_activation.occurred_at <= NEW.occurred_at
                                    WHERE other_version.merchant_id = NEW.merchant_id
                                      AND other_version.fraud_list_id = NEW.fraud_list_id
                                      AND other_version.id <> NEW.fraud_list_version_id
                                      AND NOT EXISTS (
                                          SELECT 1 FROM fraud_list_lifecycle_events other_deactivation
                                           WHERE other_deactivation.merchant_id = other_version.merchant_id
                                             AND other_deactivation.fraud_list_version_id = other_version.id
                                             AND other_deactivation.event_type = 'VERSION_DEACTIVATED'
                                             AND other_deactivation.occurred_at <= NEW.occurred_at)
                               ) THEN
                                RAISE EXCEPTION 'fraud list already has an active version';
                            END IF;
                            RETURN NEW;
                        END; $$
                        """);
            } else if (!"H2".equals(product)) {
                throw new IllegalStateException("Fraud-list activation migration is unsupported for " + product);
            }
        }
    }
}
