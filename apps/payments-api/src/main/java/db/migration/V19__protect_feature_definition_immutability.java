package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V19__protect_feature_definition_immutability extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE FUNCTION reject_immutable_feature_mutation()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $$
                        BEGIN
                            RAISE EXCEPTION 'feature lineage is immutable';
                        END;
                        $$
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_feature_definitions_immutable
                        BEFORE INSERT OR UPDATE OR DELETE ON feature_definitions
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_feature_mutation()
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_feature_values_immutable
                        BEFORE UPDATE OR DELETE ON feature_values
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_feature_mutation()
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_feature_snapshots_immutable
                        BEFORE UPDATE OR DELETE ON feature_snapshots
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_feature_mutation()
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_fraud_decisions_immutable
                        BEFORE UPDATE OR DELETE ON fraud_decisions
                        FOR EACH ROW EXECUTE FUNCTION reject_immutable_feature_mutation()
                        """);
            } else if ("H2".equals(product)) {
                statement.execute("""
                        CREATE TRIGGER trg_feature_definitions_immutable
                        BEFORE INSERT, UPDATE, DELETE ON feature_definitions
                        AS 'org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                        throws java.sql.SQLException {
                                    throw new java.sql.SQLException("feature lineage is immutable");
                                }
                            };
                        }'
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_feature_values_immutable
                        BEFORE UPDATE, DELETE ON feature_values
                        AS 'org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                        throws java.sql.SQLException {
                                    throw new java.sql.SQLException("feature lineage is immutable");
                                }
                            };
                        }'
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_feature_snapshots_immutable
                        BEFORE UPDATE, DELETE ON feature_snapshots
                        AS 'org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                        throws java.sql.SQLException {
                                    throw new java.sql.SQLException("feature lineage is immutable");
                                }
                            };
                        }'
                        """);
                statement.execute("""
                        CREATE TRIGGER trg_fraud_decisions_immutable
                        BEFORE UPDATE, DELETE ON fraud_decisions
                        AS 'org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                                        throws java.sql.SQLException {
                                    throw new java.sql.SQLException("feature lineage is immutable");
                                }
                            };
                        }'
                        """);
            } else {
                throw new IllegalStateException("Feature definition immutability is unsupported for " + product);
            }
        }
    }
}
