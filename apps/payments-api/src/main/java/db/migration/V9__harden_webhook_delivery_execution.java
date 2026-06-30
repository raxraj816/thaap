package db.migration;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V9__harden_webhook_delivery_execution extends BaseJavaMigration {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> EVENT_TYPES = List.of(
            "payment_intent.created",
            "payment_attempt.created",
            "payment_intent.captured",
            "payment_intent.canceled",
            "refund.created");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        createWebhookEventTable(connection);
        hardenWebhookEndpoints(connection);
        createEndpointSubscriptions(connection);
        hardenWebhookDeliveries(connection);
        backfillLegacyEvents(connection);
        finishDeliveryConstraints(connection);
        createAttemptTable(connection);
    }

    private void createWebhookEventTable(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE webhook_events (
                    id UUID NOT NULL,
                    merchant_id UUID NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    resource_id UUID NOT NULL,
                    resource_type VARCHAR(32) NOT NULL,
                    api_version VARCHAR(16) NOT NULL,
                    payload TEXT NOT NULL,
                    payload_sha256 VARCHAR(64) NOT NULL,
                    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                    CONSTRAINT pk_webhook_events PRIMARY KEY (id),
                    CONSTRAINT uk_webhook_events_merchant_id_id UNIQUE (merchant_id, id),
                    CONSTRAINT uk_webhook_events_delivery_lineage
                        UNIQUE (merchant_id, id, event_type, resource_id),
                    CONSTRAINT fk_webhook_events_merchant FOREIGN KEY (merchant_id)
                        REFERENCES merchants (id),
                    CONSTRAINT ck_webhook_events_event_type CHECK (
                        event_type IN (
                            'payment_intent.created',
                            'payment_attempt.created',
                            'payment_intent.captured',
                            'payment_intent.canceled',
                            'refund.created'
                        )
                    ),
                    CONSTRAINT ck_webhook_events_resource_type CHECK (
                        resource_type IN ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'REFUND')
                    ),
                    CONSTRAINT ck_webhook_events_api_version CHECK (api_version = 'v1'),
                    CONSTRAINT ck_webhook_events_payload_hash CHECK (LENGTH(payload_sha256) = 64)
                )
                """);
        execute(connection, """
                CREATE INDEX idx_webhook_events_merchant_created
                    ON webhook_events (merchant_id, created_at, id)
                """);
    }

    private void hardenWebhookEndpoints(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE webhook_endpoints ADD COLUMN operational_status VARCHAR(32)");
        execute(connection, "ALTER TABLE webhook_endpoints ADD COLUMN signing_secret_ciphertext TEXT");
        execute(connection, "ALTER TABLE webhook_endpoints ADD COLUMN signing_secret_nonce VARCHAR(64)");
        execute(connection, "ALTER TABLE webhook_endpoints ADD COLUMN signing_secret_key_version VARCHAR(32)");
        execute(connection, """
                UPDATE webhook_endpoints
                SET operational_status = CASE
                    WHEN active = TRUE THEN 'REQUIRES_SECRET_ROTATION'
                    ELSE 'DISABLED'
                END
                """);
        execute(connection, "ALTER TABLE webhook_endpoints ALTER COLUMN operational_status SET NOT NULL");
        execute(connection, "DROP INDEX idx_webhook_endpoints_merchant_active");
        execute(connection, "ALTER TABLE webhook_endpoints DROP COLUMN active");
        execute(connection, """
                ALTER TABLE webhook_endpoints
                    ADD CONSTRAINT ck_webhook_endpoints_operational_status CHECK (
                        operational_status IN ('ACTIVE', 'DISABLED', 'REQUIRES_SECRET_ROTATION')
                    )
                """);
        execute(connection, """
                ALTER TABLE webhook_endpoints
                    ADD CONSTRAINT ck_webhook_endpoints_active_secret CHECK (
                        operational_status <> 'ACTIVE'
                        OR (
                            signing_secret_ciphertext IS NOT NULL
                            AND signing_secret_nonce IS NOT NULL
                            AND signing_secret_key_version IS NOT NULL
                        )
                    )
                """);
        execute(connection, """
                CREATE INDEX idx_webhook_endpoints_merchant_status
                    ON webhook_endpoints (merchant_id, operational_status, created_at, id)
                """);
    }

    private void createEndpointSubscriptions(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE webhook_endpoint_event_subscriptions (
                    merchant_id UUID NOT NULL,
                    webhook_endpoint_id UUID NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                    CONSTRAINT pk_webhook_endpoint_event_subscriptions
                        PRIMARY KEY (merchant_id, webhook_endpoint_id, event_type),
                    CONSTRAINT fk_webhook_endpoint_event_subscriptions_endpoint FOREIGN KEY
                        (merchant_id, webhook_endpoint_id)
                        REFERENCES webhook_endpoints (merchant_id, id),
                    CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (
                        event_type IN (
                            'payment_intent.created',
                            'payment_attempt.created',
                            'payment_intent.captured',
                            'payment_intent.canceled',
                            'refund.created'
                        )
                    )
                )
                """);
        try (PreparedStatement endpointQuery = connection.prepareStatement("""
                SELECT merchant_id, id, created_at
                FROM webhook_endpoints
                """);
                ResultSet endpoints = endpointQuery.executeQuery();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO webhook_endpoint_event_subscriptions (
                            merchant_id, webhook_endpoint_id, event_type, created_at
                        )
                        VALUES (?, ?, ?, ?)
                        """)) {
            while (endpoints.next()) {
                for (String eventType : EVENT_TYPES) {
                    insert.setObject(1, endpoints.getObject("merchant_id"));
                    insert.setObject(2, endpoints.getObject("id"));
                    insert.setString(3, eventType);
                    insert.setTimestamp(4, endpoints.getTimestamp("created_at"));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
        execute(connection, """
                CREATE INDEX idx_webhook_subscriptions_event_endpoint
                    ON webhook_endpoint_event_subscriptions (event_type, webhook_endpoint_id)
                """);
    }

    private void hardenWebhookDeliveries(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE webhook_deliveries ADD COLUMN webhook_event_id UUID");
        execute(connection, "ALTER TABLE webhook_deliveries ADD COLUMN last_error_code VARCHAR(64)");
        execute(connection, "ALTER TABLE webhook_deliveries ADD COLUMN claim_token UUID");
        execute(connection, "ALTER TABLE webhook_deliveries ADD COLUMN lease_expires_at TIMESTAMP(6) WITH TIME ZONE");
        execute(connection, """
                UPDATE webhook_deliveries
                SET status = CASE
                        WHEN status = 'FAILED' THEN 'EXHAUSTED'
                        WHEN status = 'PENDING' AND next_attempt_at IS NOT NULL THEN 'RETRY_SCHEDULED'
                        ELSE status
                    END,
                    next_attempt_at = CASE
                        WHEN status IN ('FAILED', 'SUCCEEDED') THEN NULL
                        ELSE next_attempt_at
                    END,
                    delivered_at = CASE
                        WHEN status = 'SUCCEEDED' THEN delivered_at
                        ELSE NULL
                    END,
                    last_error_code = CASE
                        WHEN status = 'SUCCEEDED' THEN NULL
                        WHEN last_error IS NULL THEN NULL
                        ELSE 'LEGACY_FAILURE'
                    END
                """);
        execute(connection, "ALTER TABLE webhook_deliveries DROP COLUMN last_error");
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT uk_webhook_deliveries_merchant_id_id UNIQUE (merchant_id, id)
                """);
    }

    private void backfillLegacyEvents(Connection connection) throws Exception {
        try (PreparedStatement deliveryQuery = connection.prepareStatement("""
                SELECT id, merchant_id, event_type, resource_id, created_at
                FROM webhook_deliveries
                ORDER BY created_at, id
                """);
                ResultSet deliveries = deliveryQuery.executeQuery();
                PreparedStatement eventInsert = connection.prepareStatement("""
                        INSERT INTO webhook_events (
                            id, merchant_id, event_type, resource_id, resource_type,
                            api_version, payload, payload_sha256, occurred_at, created_at
                        )
                        VALUES (?, ?, ?, ?, ?, 'v1', ?, ?, ?, ?)
                        """);
                PreparedStatement deliveryUpdate = connection.prepareStatement("""
                        UPDATE webhook_deliveries
                        SET webhook_event_id = ?
                        WHERE id = ?
                        """)) {
            while (deliveries.next()) {
                UUID deliveryId = uuid(deliveries, "id");
                UUID merchantId = uuid(deliveries, "merchant_id");
                String eventType = deliveries.getString("event_type");
                UUID resourceId = uuid(deliveries, "resource_id");
                Instant occurredAt = deliveries.getTimestamp("created_at").toInstant();
                String resourceType = resourceType(eventType);
                String payload = payload(deliveryId, merchantId, eventType, resourceId, resourceType, occurredAt);

                eventInsert.setObject(1, deliveryId);
                eventInsert.setObject(2, merchantId);
                eventInsert.setString(3, eventType);
                eventInsert.setObject(4, resourceId);
                eventInsert.setString(5, resourceType);
                eventInsert.setString(6, payload);
                eventInsert.setString(7, sha256(payload));
                eventInsert.setTimestamp(8, Timestamp.from(occurredAt));
                eventInsert.setTimestamp(9, Timestamp.from(occurredAt));
                eventInsert.addBatch();

                deliveryUpdate.setObject(1, deliveryId);
                deliveryUpdate.setObject(2, deliveryId);
                deliveryUpdate.addBatch();
            }
            eventInsert.executeBatch();
            deliveryUpdate.executeBatch();
        }
    }

    private void finishDeliveryConstraints(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE webhook_deliveries ALTER COLUMN webhook_event_id SET NOT NULL");
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT fk_webhook_deliveries_event FOREIGN KEY
                        (merchant_id, webhook_event_id, event_type, resource_id)
                        REFERENCES webhook_events (merchant_id, id, event_type, resource_id)
                """);
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT uk_webhook_deliveries_event_endpoint
                        UNIQUE (webhook_event_id, webhook_endpoint_id)
                """);
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT ck_webhook_deliveries_status CHECK (
                        status IN (
                            'PENDING',
                            'DELIVERING',
                            'RETRY_SCHEDULED',
                            'SUCCEEDED',
                            'EXHAUSTED'
                        )
                    )
                """);
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT ck_webhook_deliveries_attempt_count CHECK (attempt_count >= 0)
                """);
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT ck_webhook_deliveries_claim CHECK (
                        (
                            status = 'DELIVERING'
                            AND claim_token IS NOT NULL
                            AND lease_expires_at IS NOT NULL
                        )
                        OR
                        (
                            status <> 'DELIVERING'
                            AND claim_token IS NULL
                            AND lease_expires_at IS NULL
                        )
                    )
                """);
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT ck_webhook_deliveries_retry_time CHECK (
                        (status = 'RETRY_SCHEDULED' AND next_attempt_at IS NOT NULL)
                        OR (status <> 'RETRY_SCHEDULED' AND next_attempt_at IS NULL)
                    )
                """);
        execute(connection, """
                ALTER TABLE webhook_deliveries
                    ADD CONSTRAINT ck_webhook_deliveries_terminal_time CHECK (
                        (
                            status = 'SUCCEEDED'
                            AND delivered_at IS NOT NULL
                        )
                        OR
                        (
                            status <> 'SUCCEEDED'
                            AND delivered_at IS NULL
                        )
                    )
                """);
        execute(connection, """
                CREATE INDEX idx_webhook_deliveries_due
                    ON webhook_deliveries (status, next_attempt_at, created_at, id)
                """);
        execute(connection, """
                CREATE INDEX idx_webhook_deliveries_expired_lease
                    ON webhook_deliveries (status, lease_expires_at, id)
                """);
        execute(connection, """
                CREATE INDEX idx_webhook_deliveries_merchant_newest
                    ON webhook_deliveries (merchant_id, created_at, id)
                """);
    }

    private void createAttemptTable(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE webhook_delivery_attempts (
                    id UUID NOT NULL,
                    merchant_id UUID NOT NULL,
                    webhook_delivery_id UUID NOT NULL,
                    attempt_number INTEGER NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    http_request_sent BOOLEAN,
                    dispatch_started_at TIMESTAMP(6) WITH TIME ZONE,
                    response_status INTEGER,
                    error_code VARCHAR(64),
                    started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP(6) WITH TIME ZONE,
                    latency_ms BIGINT,
                    CONSTRAINT pk_webhook_delivery_attempts PRIMARY KEY (id),
                    CONSTRAINT uk_webhook_delivery_attempts_merchant_id_id
                        UNIQUE (merchant_id, id),
                    CONSTRAINT uk_webhook_delivery_attempts_delivery_number
                        UNIQUE (webhook_delivery_id, attempt_number),
                    CONSTRAINT fk_webhook_delivery_attempts_delivery FOREIGN KEY
                        (merchant_id, webhook_delivery_id)
                        REFERENCES webhook_deliveries (merchant_id, id),
                    CONSTRAINT ck_webhook_delivery_attempts_number CHECK (attempt_number > 0),
                    CONSTRAINT ck_webhook_delivery_attempts_outcome CHECK (
                        outcome IN (
                            'IN_PROGRESS',
                            'SUCCEEDED',
                            'RETRYABLE_FAILURE',
                            'TERMINAL_FAILURE',
                            'OUTCOME_UNKNOWN'
                        )
                    ),
                    CONSTRAINT ck_webhook_delivery_attempts_response_status CHECK (
                        response_status IS NULL
                        OR (response_status >= 100 AND response_status <= 599)
                    ),
                    CONSTRAINT ck_webhook_delivery_attempts_latency CHECK (
                        latency_ms IS NULL OR latency_ms >= 0
                    ),
                    CONSTRAINT ck_webhook_delivery_attempts_completion CHECK (
                        (
                            outcome = 'IN_PROGRESS'
                            AND completed_at IS NULL
                            AND latency_ms IS NULL
                        )
                        OR
                        (
                            outcome <> 'IN_PROGRESS'
                            AND completed_at IS NOT NULL
                            AND latency_ms IS NOT NULL
                        )
                    ),
                    CONSTRAINT ck_webhook_delivery_attempts_dispatch_truth CHECK (
                        (
                            (
                                outcome = 'IN_PROGRESS'
                                AND (
                                    (
                                        dispatch_started_at IS NULL
                                        AND http_request_sent IS FALSE
                                    )
                                    OR
                                    (
                                        dispatch_started_at IS NOT NULL
                                        AND http_request_sent IS NULL
                                    )
                                )
                            )
                            OR
                            (
                                outcome = 'OUTCOME_UNKNOWN'
                                AND dispatch_started_at IS NOT NULL
                                AND http_request_sent IS NULL
                            )
                            OR
                            (
                                outcome NOT IN ('IN_PROGRESS', 'OUTCOME_UNKNOWN')
                                AND http_request_sent IS NOT NULL
                            )
                        )
                        AND (
                            http_request_sent IS NULL
                            OR http_request_sent IS FALSE
                            OR dispatch_started_at IS NOT NULL
                        )
                    ),
                    CONSTRAINT ck_webhook_delivery_attempts_success CHECK (
                        outcome <> 'SUCCEEDED'
                        OR (
                            http_request_sent IS TRUE
                            AND response_status BETWEEN 200 AND 299
                            AND error_code IS NULL
                        )
                    )
                )
                """);
        execute(connection, """
                CREATE INDEX idx_webhook_attempts_merchant_delivery
                    ON webhook_delivery_attempts (
                        merchant_id, webhook_delivery_id, attempt_number, id
                    )
                """);
    }

    private String resourceType(String eventType) {
        return switch (eventType) {
            case "payment_intent.created", "payment_intent.captured", "payment_intent.canceled" ->
                    "PAYMENT_INTENT";
            case "payment_attempt.created" -> "PAYMENT_ATTEMPT";
            case "refund.created" -> "REFUND";
            default -> throw new IllegalStateException("Unsupported legacy webhook event type: " + eventType);
        };
    }

    private String payload(UUID eventId, UUID merchantId, String eventType, UUID resourceId, String resourceType,
            Instant occurredAt) {
        WebhookEventPayload payload = new WebhookEventPayload(eventId, eventType,
                occurredAt.toString(), "v1", merchantId,
                new WebhookEventPayloadData(new WebhookEventPayloadObject(resourceId, resourceType)));
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Legacy webhook event payload could not be serialized", exception);
        }
    }

    @JsonPropertyOrder({"id", "type", "createdAt", "apiVersion", "merchantId", "data"})
    private record WebhookEventPayload(
            UUID id,
            String type,
            String createdAt,
            String apiVersion,
            UUID merchantId,
            WebhookEventPayloadData data) {
    }

    @JsonPropertyOrder({"object"})
    private record WebhookEventPayloadData(WebhookEventPayloadObject object) {
    }

    @JsonPropertyOrder({"id", "type"})
    private record WebhookEventPayloadObject(UUID id, String type) {
    }

    private String sha256(String payload) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private UUID uuid(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
