package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V37__extend_velocity_feature_catalogue extends BaseJavaMigration {

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(1, "velocity.card_attempts_10m", "Other logical card attempts in the prior ten minutes"),
            new Definition(2, "velocity.ip_attempts_10m", "Other logical IP attempts in the prior ten minutes"),
            new Definition(3, "velocity.customer_attempts_1h", "Other logical customer attempts in the prior hour"),
            new Definition(4, "velocity.merchant_attempts_1m", "Other merchant attempts in the prior minute"));

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PostgreSQL".equals(product)
                    ? "DROP TRIGGER trg_feature_definitions_immutable ON feature_definitions"
                    : "DROP TRIGGER trg_feature_definitions_immutable");
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO feature_definitions
                    (id, feature_code, entity_type, value_type, owner, feature_version,
                     description, max_age_ms, created_at)
                VALUES (?, ?, 'PAYMENT_ATTEMPT', 'LONG', 'FRAUD_RISK', 'v1', ?, ?, ?)
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (Definition definition : DEFINITIONS) {
                insert.setObject(1, UUID.fromString("30000000-0000-0000-0000-%012d".formatted(definition.number())));
                insert.setString(2, definition.code());
                insert.setString(3, definition.description());
                insert.setLong(4, 86_400_000L);
                insert.setTimestamp(5, now);
                insert.addBatch();
            }
            insert.executeBatch();
        } finally {
            recreateTrigger(connection, product);
        }
    }

    private void recreateTrigger(Connection connection, String product) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(product)) {
                statement.execute("""
                        CREATE TRIGGER trg_feature_definitions_immutable
                        BEFORE INSERT OR UPDATE OR DELETE ON feature_definitions
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
            } else {
                throw new IllegalStateException("Velocity feature catalogue migration is unsupported for " + product);
            }
        }
    }

    private record Definition(int number, String code, String description) {
    }
}
