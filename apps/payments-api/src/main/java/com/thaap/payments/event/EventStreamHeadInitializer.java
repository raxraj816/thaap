package com.thaap.payments.event;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventStreamHeadInitializer {

    private static final String POSTGRESQL_INSERT = """
            INSERT INTO event_stream_heads (
                merchant_id, stream_type, stream_id, current_version, updated_at)
            VALUES (?, ?, ?, 0, ?)
            ON CONFLICT (merchant_id, stream_type, stream_id) DO NOTHING
            """;
    private static final String H2_INSERT = """
            INSERT INTO event_stream_heads (
                merchant_id, stream_type, stream_id, current_version, updated_at)
            SELECT ?, ?, ?, 0, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM event_stream_heads
                WHERE merchant_id = ? AND stream_type = ? AND stream_id = ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventStreamHeadInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createIfAbsent(UUID merchantId, DomainStreamType streamType,
            UUID streamId, Instant updatedAt) {
        jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            String productName = connection.getMetaData().getDatabaseProductName();
            String statementSql;
            if ("PostgreSQL".equals(productName)) {
                statementSql = POSTGRESQL_INSERT;
            } else if ("H2".equals(productName)) {
                statementSql = H2_INSERT;
            } else {
                throw new IllegalStateException(
                        "Unsupported event stream database: " + productName);
            }
            try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
                statement.setObject(1, merchantId);
                statement.setString(2, streamType.name());
                statement.setObject(3, streamId);
                statement.setTimestamp(4, Timestamp.from(updatedAt));
                if ("H2".equals(productName)) {
                    statement.setObject(5, merchantId);
                    statement.setString(6, streamType.name());
                    statement.setObject(7, streamId);
                }
                return statement.executeUpdate();
            }
        });
    }
}
