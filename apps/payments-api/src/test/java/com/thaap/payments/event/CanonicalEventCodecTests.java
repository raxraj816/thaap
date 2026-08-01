package com.thaap.payments.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.tenant.TrafficOrigin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.erdtman.jcs.JsonCanonicalizer;

class CanonicalEventCodecTests {

    private static final UUID MERCHANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STREAM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-07-28T07:00:00.123456789Z");
    private static final Instant RECORDED_AT =
            Instant.parse("2026-07-28T07:00:01.654321999Z");
    private static final String PAYLOAD = "{\"amountMinor\":1099,"
            + "\"captureMethod\":\"AUTOMATIC\",\"currency\":\"USD\","
            + "\"customerReference\":\"customer-42\"}";
    private static final String PAYLOAD_SHA =
            "ad6f2e909273ab5dfe8ba92d871bc98770f9978c8417750c9e69fcc4f6c098aa";
    private static final String RECORD = "{\"actorId\":\"merchant-key-prefix\","
            + "\"actorType\":\"MERCHANT_API_KEY\",\"causationEventId\":null,"
            + "\"correlationId\":\"corr-001\","
            + "\"eventId\":\"00000000-0000-0000-0000-000000000101\","
            + "\"eventType\":\"payment_intent.created\",\"eventVersion\":1,"
            + "\"merchantId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"occurredAt\":\"2026-07-28T07:00:00.123456Z\","
            + "\"payloadJson\":" + PAYLOAD + ",\"payloadSha256\":\"" + PAYLOAD_SHA + "\","
            + "\"recordedAt\":\"2026-07-28T07:00:01.654321Z\","
            + "\"requestId\":\"req-001\","
            + "\"streamId\":\"00000000-0000-0000-0000-000000000002\","
            + "\"streamType\":\"PAYMENT\",\"streamVersion\":7,"
            + "\"traceId\":\"trace-001\",\"trafficOrigin\":\"MERCHANT_API\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainEventCatalogue catalogue = new DomainEventCatalogue();
    private final CanonicalEventCodec codec = new CanonicalEventCodec(catalogue, objectMapper);

    @Test
    void canonicalizesExactFrozenEnvelopeAndPayloadBytes() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("customerReference", "customer-42");
        payload.put("currency", "USD");
        payload.put("amountMinor", 1099);
        payload.put("captureMethod", "AUTOMATIC");

        EncodedDomainEvent encoded = codec.encode(command(
                DomainEventType.PAYMENT_INTENT_CREATED, payload), EVENT_ID, 7,
                RECORDED_AT);

        assertThat(new String(encoded.payloadBytes(), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
        assertThat(encoded.payloadSha256()).isEqualTo(PAYLOAD_SHA);
        assertThat(new String(encoded.eventRecordBytes(), StandardCharsets.UTF_8))
                .isEqualTo(RECORD);
        assertThat(encoded.eventRecordSha256())
                .isEqualTo("595bbf76dce5cc6bf960e5d6d2f2806d8a8007b6da56f1962a7475e3504214be");
        assertThat(encoded.occurredAt())
                .isEqualTo(Instant.parse("2026-07-28T07:00:00.123456Z"));
        assertThat(encoded.recordedAt())
                .isEqualTo(Instant.parse("2026-07-28T07:00:01.654321Z"));
        assertThat(codec.digestMatches(encoded.eventRecordBytes(),
                encoded.eventRecordSha256())).isTrue();
    }

    @Test
    void propertyOrderDoesNotChangeCanonicalBytes() {
        ObjectNode first = validCreatedPayload();
        ObjectNode second = objectMapper.createObjectNode();
        second.put("captureMethod", "AUTOMATIC");
        second.put("amountMinor", 1099);
        second.put("customerReference", "customer-42");
        second.put("currency", "USD");

        EncodedDomainEvent firstEncoded = codec.encode(command(
                DomainEventType.PAYMENT_INTENT_CREATED, first), EVENT_ID, 7,
                RECORDED_AT);
        EncodedDomainEvent secondEncoded = codec.encode(command(
                DomainEventType.PAYMENT_INTENT_CREATED, second), EVENT_ID, 7,
                RECORDED_AT);

        assertThat(firstEncoded.payloadBytes()).isEqualTo(secondEncoded.payloadBytes());
        assertThat(firstEncoded.eventRecordBytes()).isEqualTo(secondEncoded.eventRecordBytes());
    }

    @Test
    void decoderRequiresTheExactFrozenEnvelopeFieldSet() throws Exception {
        CanonicalDomainEventRecord decoded = codec.decodeAndValidate(
                RECORD.getBytes(StandardCharsets.UTF_8),
                "595bbf76dce5cc6bf960e5d6d2f2806d8a8007b6da56f1962a7475e3504214be");
        assertThat(decoded.eventId()).isEqualTo(EVENT_ID);
        assertThat(decoded.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(decoded.streamVersion()).isEqualTo(7);
        assertThat(decoded.payloadSha256()).isEqualTo(PAYLOAD_SHA);

        ObjectNode unknownEnvelope = (ObjectNode) objectMapper.readTree(RECORD);
        unknownEnvelope.put("futureEnvelopeField", "not-registered");
        assertProtocolViolation(canonicalBytes(unknownEnvelope),
                DomainEventProtocolViolation.INVALID_ENVELOPE);

        ObjectNode missingNullableEnvelope = (ObjectNode) objectMapper.readTree(RECORD);
        missingNullableEnvelope.remove("traceId");
        assertProtocolViolation(canonicalBytes(missingNullableEnvelope),
                DomainEventProtocolViolation.INVALID_ENVELOPE);
    }

    @Test
    void rejectsEveryClosedProducerSchemaPartition() {
        ObjectNode missingRequired = objectMapper.createObjectNode();
        missingRequired.put("currency", "USD");
        missingRequired.put("captureMethod", "AUTOMATIC");
        assertInvalid(missingRequired, "missing a required field");

        ObjectNode unknown = validCreatedPayload();
        unknown.put("futureField", "not-yet-registered");
        assertInvalid(unknown, "unknown field");

        ObjectNode prohibited = validCreatedPayload();
        prohibited.put("metadata", "unsafe");
        assertInvalid(prohibited, "prohibited field");

        ObjectNode explicitNull = validCreatedPayload();
        explicitNull.putNull("customerReference");
        assertInvalid(explicitNull, "cannot be null");

        ObjectNode decimalAmount = validCreatedPayload();
        decimalAmount.put("amountMinor", 10.5);
        assertInvalid(decimalAmount, "must be an integer");

        ObjectNode unsafeInteger = validCreatedPayload();
        unsafeInteger.put("amountMinor", CanonicalEventCodec.MAX_I_JSON_SAFE_INTEGER + 1);
        assertInvalid(unsafeInteger, "outside its semantic range");

        ObjectNode invalidUnicode = validCreatedPayload();
        invalidUnicode.put("currency", "\uD800");
        assertInvalid(invalidUnicode, "invalid surrogate");
    }

    @Test
    void rejectsOversizedPayloadBeforePersistingAnyBytes() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "REVIEW");
        payload.put("riskLevel", "HIGH");
        payload.put("rulesetVersion", "rules-v1");
        ArrayNode reasons = payload.putArray("reasonCodes");
        String largeReason = "r".repeat(1_024);
        for (int index = 0; index < 100; index++) {
            reasons.add(largeReason);
        }

        assertThatThrownBy(() -> codec.encode(command(
                DomainEventType.FRAUD_DECISION_CREATED, payload), EVENT_ID, 1,
                RECORDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload size is invalid");
    }

    @Test
    void rejectsRepresentationAndLineageBoundaryViolations() {
        assertThatThrownBy(() -> codec.encode(command(
                DomainEventType.PAYMENT_INTENT_CREATED, validCreatedPayload()),
                EVENT_ID, 0, RECORDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe canonical JSON range");

        assertThatThrownBy(() -> codec.encode(new DomainEventCommand(MERCHANT_ID,
                DomainStreamType.PAYMENT, STREAM_ID,
                DomainEventType.PAYMENT_INTENT_CREATED, 2, OCCURRED_AT,
                "corr-001", null, null, null, null, null,
                validCreatedPayload()), EVENT_ID, 1, RECORDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported domain event type/version");

        assertThatThrownBy(() -> codec.encode(command(
                DomainEventType.PAYMENT_INTENT_CREATED, validCreatedPayload()),
                EVENT_ID, 1, Instant.parse("2026-07-28T06:59:59Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot precede occurrence time");

        assertThat(codec.digestMatches(RECORD.getBytes(StandardCharsets.UTF_8),
                "A".repeat(64))).isFalse();
        assertThat(codec.digestMatches(null, PAYLOAD_SHA)).isFalse();
    }

    @Test
    void rejectsEverySemanticNumberTimestampAndIdentityBoundaryPartition() {
        ObjectNode negativeMoney = validCreatedPayload();
        negativeMoney.put("amountMinor", -1);
        assertInvalid(DomainEventType.PAYMENT_INTENT_CREATED, negativeMoney,
                "outside its semantic range");

        ObjectNode zeroAttempt = attemptPayload(0);
        assertInvalid(DomainEventType.PAYMENT_ATTEMPT_COMPLETED, zeroAttempt,
                "outside its semantic range");
        ObjectNode wrappedAttempt = attemptPayload((long) Integer.MAX_VALUE + 1);
        assertInvalid(DomainEventType.PAYMENT_ATTEMPT_COMPLETED, wrappedAttempt,
                "outside its semantic range");
        assertThat(codec.encode(command(DomainEventType.PAYMENT_ATTEMPT_COMPLETED,
                attemptPayload(Integer.MAX_VALUE)), EVENT_ID, 1, RECORDED_AT)).isNotNull();

        assertInvalid(DomainEventType.FRAUD_DECISION_CREATED, fraudPayload(-1),
                "outside its semantic range");
        assertInvalid(DomainEventType.FRAUD_DECISION_CREATED, fraudPayload(10_001),
                "outside its semantic range");
        assertThat(codec.encode(command(DomainEventType.FRAUD_DECISION_CREATED,
                fraudPayload(0)), EVENT_ID, 1, RECORDED_AT)).isNotNull();
        assertThat(codec.encode(command(DomainEventType.FRAUD_DECISION_CREATED,
                fraudPayload(10_000)), EVENT_ID, 1, RECORDED_AT)).isNotNull();

        ObjectNode invalidInstant = labelPayload("not-an-instant");
        assertInvalid(DomainEventType.FRAUD_LABEL_RECORDED, invalidInstant,
                "timestamp is invalid");
        ObjectNode overPreciseInstant = labelPayload("2026-07-28T07:00:00.123456789Z");
        assertInvalid(DomainEventType.FRAUD_LABEL_RECORDED, overPreciseInstant,
                "microsecond precision");
        assertThat(codec.encode(command(DomainEventType.FRAUD_LABEL_RECORDED,
                labelPayload("2026-07-28T07:00:00.123456Z")), EVENT_ID, 1,
                RECORDED_AT)).isNotNull();

        ObjectNode invalidReference = labelPayload("2026-07-28T07:00:00.123456Z");
        invalidReference.put("supersedesLabelReference", "NOT-A-UUID");
        assertInvalid(DomainEventType.FRAUD_LABEL_SUPERSEDED, invalidReference,
                "UUID is invalid");
        ObjectNode nonCanonicalReference = labelPayload("2026-07-28T07:00:00.123456Z");
        nonCanonicalReference.put("supersedesLabelReference",
                "00000000-0000-0000-0000-000000000ABC");
        assertInvalid(DomainEventType.FRAUD_LABEL_SUPERSEDED, nonCanonicalReference,
                "UUID is invalid");
    }

    @Test
    void frozenCatalogueHasExactlyOneSchemaForEveryExactWireType() {
        Set<String> expected = Set.of(
                "payment_intent.created", "payment_intent.canceled",
                "payment_attempt.completed", "fraud_decision.created",
                "authorization.created", "authorization.voided",
                "capture.created", "refund.created", "chargeback.created",
                "authentication_challenge.created", "authentication_challenge.completed",
                "manual_review.opened", "manual_review.assigned", "manual_review.resolved",
                "fraud_label.recorded", "fraud_label.superseded",
                "risk_incident.opened", "risk_incident.resolved",
                "merchant_risk_profile.activated", "merchant_risk_profile.rolled_back",
                "risk_policy.activated", "risk_policy.rolled_back",
                "authored_rule.activated", "authored_rule.rolled_back",
                "authored_rule.disabled", "safety_exception.activated",
                "safety_exception.expired", "safety_exception.disabled",
                "dispute_workflow.opened", "dispute_evidence.submitted",
                "dispute_outcome.recorded", "dispute_workflow.expired",
                "webhook_endpoint.verified", "webhook_endpoint.disabled",
                "webhook_endpoint.secret_rotated", "webhook_delivery.exhausted",
                "webhook_redelivery.requested", "webhook_redelivery.succeeded",
                "post_authorization_signal.recorded", "developer_scenario.created",
                "developer_scenario.completed", "developer_scenario.failed");
        Set<String> actual = catalogue.schemas().keySet().stream()
                .map(DomainEventType::wireValue).collect(Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(catalogue.schemas()).hasSize(42);
        assertThat(catalogue.schemas().values())
                .allMatch(schema -> schema.eventVersion()
                        == DomainEventCatalogue.INITIAL_EVENT_VERSION);
        assertThat(DomainEventType.MANUAL_REVIEW_OPENED.streamType())
                .isEqualTo(DomainStreamType.PAYMENT);
        assertThatThrownBy(() -> DomainEventType.fromWireValue("review.closed"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DomainEventCommand command(DomainEventType eventType, ObjectNode payload) {
        return new DomainEventCommand(MERCHANT_ID, eventType.streamType(), STREAM_ID,
                eventType, DomainEventCatalogue.INITIAL_EVENT_VERSION, OCCURRED_AT,
                "corr-001", null, "req-001", "trace-001",
                new DomainEventActor(DomainEventActorType.MERCHANT_API_KEY,
                        "merchant-key-prefix"), TrafficOrigin.MERCHANT_API, payload);
    }

    private ObjectNode validCreatedPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 1099);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        payload.put("customerReference", "customer-42");
        return payload;
    }

    private ObjectNode attemptPayload(long attemptNumber) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("attemptNumber", attemptNumber);
        payload.put("outcome", "AUTHORIZED");
        return payload;
    }

    private ObjectNode fraudPayload(int riskScoreBasisPoints) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "ALLOW");
        payload.put("riskLevel", "LOW");
        payload.putArray("reasonCodes").add("NO_RULE_MATCH");
        payload.put("rulesetVersion", "rules_v1");
        payload.put("riskScore", riskScoreBasisPoints);
        payload.put("featureCatalogueVersion", "features_v1");
        return payload;
    }

    private ObjectNode labelPayload(String availableAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("label", "FRAUD");
        payload.put("source", "MANUAL_REVIEW");
        payload.put("availableAt", availableAt);
        return payload;
    }

    private void assertInvalid(ObjectNode payload, String expectedMessage) {
        assertInvalid(DomainEventType.PAYMENT_INTENT_CREATED, payload, expectedMessage);
    }

    private void assertInvalid(DomainEventType eventType, ObjectNode payload,
            String expectedMessage) {
        assertThatThrownBy(() -> codec.encode(command(eventType, payload), EVENT_ID, 1,
                RECORDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    private byte[] canonicalBytes(ObjectNode value) throws Exception {
        return new JsonCanonicalizer(objectMapper.writeValueAsBytes(value)).getEncodedUTF8();
    }

    private void assertProtocolViolation(byte[] bytes,
            DomainEventProtocolViolation expectedViolation) throws Exception {
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThatThrownBy(() -> codec.decodeAndValidate(bytes, digest))
                .isInstanceOfSatisfying(DomainEventProtocolException.class,
                        exception -> assertThat(exception.violation())
                                .isEqualTo(expectedViolation));
    }
}
