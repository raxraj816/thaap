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

public class V33__extend_safe_risk_feature_catalogue extends BaseJavaMigration {

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(1, "risk.context_present", "BOOLEAN", "Whether merchant-observed RiskContext was supplied"),
            new Definition(2, "risk.ip_country", "STRING", "Merchant-observed IP country classification"),
            new Definition(3, "risk.shipping_country", "STRING", "Merchant-observed shipping country classification"),
            new Definition(4, "risk.checkout_channel", "STRING", "Closed merchant-observed checkout channel"),
            new Definition(5, "risk.customer_account_age_days", "LONG", "Merchant-observed account age in days"),
            new Definition(6, "risk.login_age_minutes", "LONG", "Merchant-observed latest login age in minutes"),
            new Definition(7, "risk.bot_score_bps", "LONG", "Merchant-observed bot score in basis points"),
            new Definition(8, "risk.captcha_outcome", "STRING", "Closed merchant-observed CAPTCHA outcome"),
            new Definition(9, "risk.card_ip_country_match", "BOOLEAN", "Card and observed IP country equality"),
            new Definition(10, "risk.card_shipping_country_match", "BOOLEAN", "Card and shipping country equality"));

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
                VALUES (?, ?, 'PAYMENT_ATTEMPT', ?, 'FRAUD_RISK', 'v1', ?, NULL, ?)
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (Definition definition : DEFINITIONS) {
                insert.setObject(1, UUID.fromString("29000000-0000-0000-0000-%012d".formatted(definition.number())));
                insert.setString(2, definition.code());
                insert.setString(3, definition.type());
                insert.setString(4, definition.description());
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
                throw new IllegalStateException("Risk feature catalogue migration is unsupported for " + product);
            }
        }
    }

    private record Definition(int number, String code, String type, String description) {
    }
}
