package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V21__protect_rule_lineage_immutability extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION reject_immutable_rule_mutation()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $$
                        BEGIN
                            RAISE EXCEPTION 'rule lineage is immutable';
                        END;
                        $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_fraud_rules_immutable
                        BEFORE INSERT OR UPDATE OR DELETE ON fraud_rules
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_rule_mutation()
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_rule_execution_results_immutable
                        BEFORE UPDATE OR DELETE ON rule_execution_results
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_rule_mutation()
                        """);
            } else if ("H2".equals(product)) {
                statement.execute(h2Trigger("trg_fraud_rules_immutable", "fraud_rules", "INSERT, UPDATE, DELETE"));
                statement.execute(h2Trigger("trg_rule_execution_results_immutable", "rule_execution_results",
                        "UPDATE, DELETE"));
            } else {
                throw new IllegalStateException("Rule lineage immutability is unsupported for " + product);
            }
        }
    }

    private String h2Trigger(String triggerName, String tableName, String operations) {
        return """
                CREATE TRIGGER %s
                BEFORE %s ON %s
                AS 'org.h2.api.Trigger create() {
                    return new org.h2.api.Trigger() {
                        public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                throws java.sql.SQLException {
                            throw new java.sql.SQLException("rule lineage is immutable");
                        }
                    };
                }'
                """.formatted(triggerName, operations, tableName);
    }
}
