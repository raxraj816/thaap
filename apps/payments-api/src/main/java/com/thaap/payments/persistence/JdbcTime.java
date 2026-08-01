package com.thaap.payments.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Normalizes JDBC timestamp handling across supported databases.
 *
 * <p>PostgreSQL's driver does not promise direct {@code timestamptz} to
 * {@link Instant} conversion through {@code ResultSet#getObject}. Reading the
 * JDBC {@link Timestamp} representation first preserves the instant and keeps
 * nullable columns portable across PostgreSQL and H2.</p>
 */
public final class JdbcTime {

    private JdbcTime() {
    }

    public static Instant instant(ResultSet resultSet, int columnIndex) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnIndex);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static Instant instant(ResultSet resultSet, String columnLabel) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnLabel);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
