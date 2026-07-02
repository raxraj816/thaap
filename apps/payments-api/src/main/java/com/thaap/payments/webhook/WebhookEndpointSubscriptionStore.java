package com.thaap.payments.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookEndpointSubscriptionStore {

    private final JdbcTemplate jdbcTemplate;

    public WebhookEndpointSubscriptionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(UUID merchantId, UUID endpointId, Set<WebhookEventType> eventTypes, Instant createdAt) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO webhook_endpoint_event_subscriptions (
                    merchant_id, webhook_endpoint_id, event_type, created_at
                )
                VALUES (?, ?, ?, ?)
                """, eventTypes, eventTypes.size(), (statement, eventType) -> {
                    statement.setObject(1, merchantId);
                    statement.setObject(2, endpointId);
                    statement.setString(3, eventType.wireValue());
                    statement.setTimestamp(4, Timestamp.from(createdAt));
                });
    }

    public Set<String> findWireValues(UUID merchantId, UUID endpointId) {
        List<String> values = jdbcTemplate.queryForList("""
                SELECT event_type
                FROM webhook_endpoint_event_subscriptions
                WHERE merchant_id = ?
                  AND webhook_endpoint_id = ?
                ORDER BY event_type
                """, String.class, merchantId, endpointId);
        return new LinkedHashSet<>(values);
    }
}
