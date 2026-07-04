package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V12__enforce_webhook_endpoint_url_identity extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        rejectDuplicateEndpointUrls(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE webhook_endpoints
                        ADD CONSTRAINT uk_webhook_endpoints_merchant_url
                        UNIQUE (merchant_id, url)
                    """);
        }
    }

    private void rejectDuplicateEndpointUrls(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM (
                    SELECT merchant_id, url
                    FROM webhook_endpoints
                    GROUP BY merchant_id, url
                    HAVING COUNT(*) > 1
                ) duplicate_urls
                """);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            long duplicateGroups = resultSet.getLong(1);
            if (duplicateGroups > 0) {
                throw new SQLException(
                        "V12 cannot enforce webhook endpoint URL identity while "
                                + duplicateGroups
                                + " tenant-scoped duplicate URL group(s) exist; "
                                + "disable and explicitly remediate duplicates before retrying");
            }
        }
    }
}
