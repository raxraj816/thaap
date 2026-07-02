package com.thaap.payments.webhook;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.security.SecretHasher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WebhookEventEnvelopeFactory {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    public WebhookEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebhookEvent create(UUID merchantId, WebhookEventType eventType, UUID resourceId, Instant occurredAt) {
        Instant canonicalOccurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        UUID eventId = UUID.randomUUID();
        String payload = payload(eventId, merchantId, eventType, resourceId, canonicalOccurredAt);
        return new WebhookEvent(eventId, merchantId, eventType, resourceId, payload,
                SecretHasher.sha256(payload), canonicalOccurredAt);
    }

    private String payload(UUID eventId, UUID merchantId, WebhookEventType eventType, UUID resourceId,
            Instant occurredAt) {
        WebhookEventPayload payload = new WebhookEventPayload(eventId, eventType.wireValue(),
                occurredAt.toString(), "v1", merchantId,
                new WebhookEventPayloadData(new WebhookEventPayloadObject(
                        resourceId, eventType.resourceType().name())));
        try {
            String serializedPayload = objectMapper.writeValueAsString(payload);
            int payloadBytes = serializedPayload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("Webhook event payload exceeded "
                        + MAX_PAYLOAD_BYTES + " UTF-8 bytes");
            }
            return serializedPayload;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Webhook event payload could not be serialized", exception);
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
}
