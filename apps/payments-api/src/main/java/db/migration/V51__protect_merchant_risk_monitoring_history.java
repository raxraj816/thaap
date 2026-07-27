package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V51__protect_merchant_risk_monitoring_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_merchant_risk_monitoring_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Merchant risk monitoring history is append-only';
                    END; $$
                    """);
            for (String table : new String[] {"fraud_taxonomy_definitions",
                    "merchant_risk_metric_snapshots", "merchant_risk_profile_recommendations"}) {
                statement.execute("CREATE TRIGGER trg_" + table
                        + "_append_only BEFORE UPDATE OR DELETE ON " + table
                        + " FOR EACH ROW EXECUTE FUNCTION "
                        + "reject_merchant_risk_monitoring_history_mutation()");
            }
            statement.execute("""
                    CREATE FUNCTION validate_merchant_risk_profile_recommendation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE snapshot_row merchant_risk_metric_snapshots%ROWTYPE;
                    BEGIN
                        SELECT * INTO snapshot_row
                          FROM merchant_risk_metric_snapshots
                         WHERE merchant_id = NEW.merchant_id
                           AND id = NEW.metric_snapshot_id;
                        IF NOT FOUND OR snapshot_row.traffic_origin_policy <> 'MERCHANT_API_ONLY' THEN
                            RAISE EXCEPTION 'Profile recommendation requires a live-only metric snapshot';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_merchant_risk_profile_recommendation_semantics
                    BEFORE INSERT ON merchant_risk_profile_recommendations
                    FOR EACH ROW EXECUTE FUNCTION validate_merchant_risk_profile_recommendation()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_new_fraud_label_taxonomy()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.taxonomy_version IS DISTINCT FROM 'fraud_taxonomy_v1' THEN
                            RAISE EXCEPTION 'New fraud labels require the active taxonomy version';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_new_fraud_label_taxonomy
                    BEFORE INSERT ON fraud_labels
                    FOR EACH ROW EXECUTE FUNCTION validate_new_fraud_label_taxonomy()
                    """);
        }
    }
}
