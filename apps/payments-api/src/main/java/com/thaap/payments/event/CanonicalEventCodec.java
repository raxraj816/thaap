package com.thaap.payments.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.tenant.TrafficOrigin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

@Component
public final class CanonicalEventCodec {

    static final long MAX_I_JSON_SAFE_INTEGER = 9_007_199_254_740_991L;
    static final int MAX_PAYLOAD_BYTES = 32_768;
    static final int MAX_RECORD_BYTES = 65_536;
    private static final int MAX_STRING_CHARACTERS = 1_024;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "actorId", "actorType", "causationEventId", "correlationId", "eventId",
            "eventType", "eventVersion", "merchantId", "occurredAt", "payloadJson",
            "payloadSha256", "recordedAt", "requestId", "streamId", "streamType",
            "streamVersion", "traceId", "trafficOrigin");
    private static final Set<String> PROHIBITED_FIELDS = Set.of(
            "pan", "cardnumber", "cvv", "cvc", "email", "phone", "apikey",
            "credential", "secret", "password", "token", "idempotencykey", "note",
            "metadata", "ipaddress", "deviceid", "sessionid", "riskcontext");

    private final DomainEventCatalogue catalogue;
    private final ObjectMapper objectMapper;

    public CanonicalEventCodec(DomainEventCatalogue catalogue, ObjectMapper objectMapper) {
        this.catalogue = catalogue;
        this.objectMapper = objectMapper;
    }

    public EncodedDomainEvent encode(DomainEventCommand command, UUID eventId,
            long streamVersion, Instant recordedAt) {
        if (streamVersion <= 0 || streamVersion > MAX_I_JSON_SAFE_INTEGER) {
            throw new IllegalArgumentException("Stream version is outside the safe canonical JSON range");
        }
        DomainEventSchema schema = catalogue.require(command.eventType(), command.eventVersion());
        ObjectNode payload = command.payload();
        validatePayload(schema, payload);
        byte[] payloadBytes = canonicalize(payload, MAX_PAYLOAD_BYTES, "payload");
        String payloadDigest = sha256(payloadBytes);
        Instant normalizedOccurredAt = EventTimePrecision.toPostgreSqlMicroseconds(command.occurredAt());
        Instant normalizedRecordedAt = EventTimePrecision.toPostgreSqlMicroseconds(recordedAt);
        if (normalizedRecordedAt.isBefore(normalizedOccurredAt)) {
            throw new IllegalArgumentException("Recorded time cannot precede occurrence time");
        }
        ObjectNode record = objectMapper.createObjectNode();
        putActor(record, command.actor());
        putNullableUuid(record, "causationEventId", command.causationEventId());
        record.put("correlationId", command.correlationId());
        record.put("eventId", eventId.toString());
        record.put("eventType", command.eventType().wireValue());
        record.put("eventVersion", command.eventVersion());
        record.put("merchantId", command.merchantId().toString());
        record.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(normalizedOccurredAt));
        record.set("payloadJson", payload);
        record.put("payloadSha256", payloadDigest);
        record.put("recordedAt", DateTimeFormatter.ISO_INSTANT.format(normalizedRecordedAt));
        putNullableString(record, "requestId", command.requestId());
        record.put("streamId", command.streamId().toString());
        record.put("streamType", command.streamType().name());
        record.put("streamVersion", streamVersion);
        putNullableString(record, "traceId", command.traceId());
        putNullableString(record, "trafficOrigin",
                command.trafficOrigin() == null ? null : command.trafficOrigin().name());
        byte[] recordBytes = canonicalize(record, MAX_RECORD_BYTES, "event record");
        return new EncodedDomainEvent(normalizedOccurredAt, normalizedRecordedAt,
                payloadBytes, payloadDigest, recordBytes, sha256(recordBytes));
    }

    public boolean digestMatches(byte[] bytes, String expectedLowercaseHex) {
        if (bytes == null || expectedLowercaseHex == null) {
            return false;
        }
        byte[] actual = sha256(bytes).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedLowercaseHex.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    public CanonicalDomainEventRecord decodeAndValidate(byte[] eventRecordBytes,
            String expectedRecordSha256) {
        if (eventRecordBytes == null || eventRecordBytes.length < 2
                || eventRecordBytes.length > MAX_RECORD_BYTES) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.PAYLOAD_LIMIT_EXCEEDED);
        }
        if (!digestMatches(eventRecordBytes, expectedRecordSha256)) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.RECORD_DIGEST_MISMATCH);
        }
        try {
            byte[] canonicalBytes = new JsonCanonicalizer(eventRecordBytes).getEncodedUTF8();
            if (!Arrays.equals(canonicalBytes, eventRecordBytes)) {
                throw new DomainEventProtocolException(
                        DomainEventProtocolViolation.INVALID_ENVELOPE);
            }
            JsonParser parser = objectMapper.getFactory().createParser(eventRecordBytes);
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode parsed = objectMapper.readTree(parser);
            if (!(parsed instanceof ObjectNode record)) {
                throw new DomainEventProtocolException(
                        DomainEventProtocolViolation.INVALID_ENVELOPE);
            }
            return decodeRecord(record, eventRecordBytes, expectedRecordSha256);
        } catch (DomainEventProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE, exception);
        }
    }

    private CanonicalDomainEventRecord decodeRecord(ObjectNode record, byte[] eventRecordBytes,
            String eventRecordSha256) {
        Set<String> actualEnvelopeFields = new HashSet<>();
        record.fieldNames().forEachRemaining(actualEnvelopeFields::add);
        if (!actualEnvelopeFields.equals(ENVELOPE_FIELDS)) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        UUID eventId = uuid(record, "eventId");
        UUID merchantId = uuid(record, "merchantId");
        DomainStreamType streamType = enumValue(record, "streamType", DomainStreamType.class);
        UUID streamId = uuid(record, "streamId");
        long streamVersion = positiveSafeLong(record, "streamVersion");
        DomainEventType eventType;
        try {
            eventType = DomainEventType.fromWireValue(requiredText(record, "eventType", 80));
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.UNSUPPORTED_EVENT_TYPE, exception);
        }
        int eventVersion = positiveInteger(record, "eventVersion");
        if (eventVersion != DomainEventCatalogue.INITIAL_EVENT_VERSION) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.UNSUPPORTED_EVENT_VERSION);
        }
        if (eventType.streamType() != streamType) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.TENANT_STREAM_DISAGREEMENT);
        }
        Instant occurredAt = instant(record, "occurredAt");
        Instant recordedAt = instant(record, "recordedAt");
        if (!occurredAt.equals(EventTimePrecision.toPostgreSqlMicroseconds(occurredAt))
                || !recordedAt.equals(EventTimePrecision.toPostgreSqlMicroseconds(recordedAt))
                || recordedAt.isBefore(occurredAt)) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        String correlationId = requiredText(record, "correlationId", 128);
        UUID causationEventId = nullableUuid(record, "causationEventId");
        String requestId = nullableText(record, "requestId", 128);
        String traceId = nullableText(record, "traceId", 128);
        DomainEventActor actor = actor(record);
        TrafficOrigin trafficOrigin = nullableEnum(record, "trafficOrigin", TrafficOrigin.class);
        JsonNode payloadNode = record.get("payloadJson");
        if (!(payloadNode instanceof ObjectNode payload)) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_SCHEMA);
        }
        try {
            DomainEventSchema schema = catalogue.require(eventType, eventVersion);
            validatePayload(schema, payload);
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_SCHEMA, exception);
        }
        byte[] payloadBytes = canonicalize(payload, MAX_PAYLOAD_BYTES, "payload");
        String payloadSha256 = requiredText(record, "payloadSha256", 64);
        if (!digestMatches(payloadBytes, payloadSha256)) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.PAYLOAD_DIGEST_MISMATCH);
        }
        return new CanonicalDomainEventRecord(eventId, merchantId, streamType, streamId,
                streamVersion, eventType, eventVersion, occurredAt, recordedAt, correlationId,
                causationEventId, requestId, traceId, actor, trafficOrigin, payload,
                payloadSha256, eventRecordBytes, eventRecordSha256);
    }

    private UUID uuid(ObjectNode record, String field) {
        try {
            return UUID.fromString(requiredText(record, field, 36));
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE, exception);
        }
    }

    private UUID nullableUuid(ObjectNode record, String field) {
        String value = nullableText(record, field, 36);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE, exception);
        }
    }

    private Instant instant(ObjectNode record, String field) {
        try {
            return Instant.parse(requiredText(record, field, 40));
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE, exception);
        }
    }

    private long positiveSafeLong(ObjectNode record, String field) {
        JsonNode value = record.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        long result = value.longValue();
        if (result <= 0 || result > MAX_I_JSON_SAFE_INTEGER) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        return result;
    }

    private int positiveInteger(ObjectNode record, String field) {
        JsonNode value = record.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() <= 0) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        return value.intValue();
    }

    private String requiredText(ObjectNode record, String field, int maximumLength) {
        String value = nullableText(record, field, maximumLength);
        if (value == null) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        return value;
    }

    private String nullableText(ObjectNode record, String field, int maximumLength) {
        JsonNode value = record.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximumLength) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        validateUnicode(value.textValue(), field);
        return value.textValue();
    }

    private DomainEventActor actor(ObjectNode record) {
        JsonNode typeNode = record.get("actorType");
        JsonNode idNode = record.get("actorId");
        boolean typeMissing = typeNode == null || typeNode.isNull();
        boolean idMissing = idNode == null || idNode.isNull();
        if (typeMissing && idMissing) {
            return null;
        }
        if (typeMissing || idMissing) {
            throw new DomainEventProtocolException(DomainEventProtocolViolation.INVALID_ENVELOPE);
        }
        DomainEventActorType type = enumValue(record, "actorType", DomainEventActorType.class);
        return new DomainEventActor(type, requiredText(record, "actorId", 128));
    }

    private <T extends Enum<T>> T enumValue(ObjectNode record, String field, Class<T> type) {
        String value = requiredText(record, field, 64);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE, exception);
        }
    }

    private <T extends Enum<T>> T nullableEnum(ObjectNode record, String field, Class<T> type) {
        String value = nullableText(record, field, 64);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new DomainEventProtocolException(
                    DomainEventProtocolViolation.INVALID_ENVELOPE, exception);
        }
    }

    private void validatePayload(DomainEventSchema schema, ObjectNode payload) {
        Set<String> actualFields = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            validateUnicode(name, "field name");
            if (PROHIBITED_FIELDS.contains(normalizeFieldName(name))) {
                throw new IllegalArgumentException("Domain event payload contains a prohibited field");
            }
            DomainEventFieldType type = schema.requiredFields().get(name);
            if (type == null) {
                type = schema.optionalFields().get(name);
            }
            if (type == null) {
                throw new IllegalArgumentException("Domain event payload contains an unknown field");
            }
            validateValue(type, field.getValue());
            actualFields.add(name);
        }
        if (!actualFields.containsAll(schema.requiredFields().keySet())) {
            throw new IllegalArgumentException("Domain event payload is missing a required field");
        }
    }

    private void validateValue(DomainEventFieldType type, JsonNode value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Domain event payload fields cannot be null in V1");
        }
        switch (type) {
            case STRING -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("Domain event payload field must be a string");
                }
                validateString(value.textValue());
            }
            case SAFE_INTEGER -> validateSafeInteger(value,
                    -MAX_I_JSON_SAFE_INTEGER, MAX_I_JSON_SAFE_INTEGER);
            case NON_NEGATIVE_SAFE_INTEGER -> validateSafeInteger(value, 0,
                    MAX_I_JSON_SAFE_INTEGER);
            case POSITIVE_SAFE_INTEGER -> validateSafeInteger(value, 1,
                    MAX_I_JSON_SAFE_INTEGER);
            case POSITIVE_INT32 -> validateSafeInteger(value, 1, Integer.MAX_VALUE);
            case RISK_SCORE_BASIS_POINTS -> validateSafeInteger(value, 0, 10_000);
            case INSTANT_MICROSECONDS -> validateInstant(value);
            case UUID -> validateUuid(value);
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException("Domain event payload field must be a Boolean");
                }
            }
            case STRING_ARRAY -> validateStringArray(value);
        }
    }

    private void validateSafeInteger(JsonNode value, long minimum, long maximum) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Domain event payload field must be an integer");
        }
        long number = value.longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("Domain event integer is outside its semantic range");
        }
    }

    private void validateInstant(JsonNode value) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Domain event payload timestamp must be a string");
        }
        validateString(value.textValue());
        try {
            Instant instant = Instant.parse(value.textValue());
            if (!instant.equals(EventTimePrecision.toPostgreSqlMicroseconds(instant))) {
                throw new IllegalArgumentException(
                        "Domain event payload timestamp must use database microsecond precision");
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Domain event payload timestamp is invalid",
                    exception);
        }
    }

    private void validateUuid(JsonNode value) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Domain event payload UUID must be a string");
        }
        validateString(value.textValue());
        try {
            UUID parsed = UUID.fromString(value.textValue());
            if (!parsed.toString().equals(value.textValue())) {
                throw new IllegalArgumentException("Domain event payload UUID is not canonical");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Domain event payload UUID is invalid", exception);
        }
    }

    private void validateStringArray(JsonNode value) {
        if (!(value instanceof ArrayNode array) || array.size() > MAX_ARRAY_ITEMS) {
            throw new IllegalArgumentException("Domain event payload array is invalid or too large");
        }
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Domain event payload array items must be strings");
            }
            validateString(item.textValue());
        }
    }

    private void validateString(String value) {
        if (value.length() > MAX_STRING_CHARACTERS) {
            throw new IllegalArgumentException("Domain event payload string is too large");
        }
        validateUnicode(value, "string");
    }

    private void validateUnicode(String value, String description) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Domain event " + description + " contains an invalid surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Domain event " + description + " contains an invalid surrogate");
            }
        }
    }

    private byte[] canonicalize(JsonNode value, int maximumBytes, String boundary) {
        try {
            byte[] input = objectMapper.writeValueAsBytes(value);
            byte[] canonical = new JsonCanonicalizer(input).getEncodedUTF8();
            if (canonical.length < 2 || canonical.length > maximumBytes) {
                throw new IllegalArgumentException("Canonical domain event " + boundary + " size is invalid");
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Domain event value is not valid canonical JSON", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeFieldName(String field) {
        return field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void putActor(ObjectNode record, DomainEventActor actor) {
        if (actor == null) {
            record.putNull("actorType");
            record.putNull("actorId");
            return;
        }
        record.put("actorType", actor.type().name());
        record.put("actorId", actor.id());
    }

    private void putNullableUuid(ObjectNode record, String field, UUID value) {
        if (value == null) {
            record.putNull(field);
        } else {
            record.put(field, value.toString());
        }
    }

    private void putNullableString(ObjectNode record, String field, String value) {
        if (value == null) {
            record.putNull(field);
        } else {
            record.put(field, value);
        }
    }
}
