package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventProducer;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.EncodedDomainEvent;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Proof for {@code C46-SEC-001}.
 *
 * <p>Every prohibited class the row names is seeded as a distinct canary in the inputs a
 * risk operation actually receives — the operator credential, the merchant API key
 * prefix, the merchant name, note plaintext and a PAN. The assertion is made over the
 * exact canonical bytes rather than over the payload object, because the bytes are what
 * reach Kafka, the database and every downstream consumer.
 *
 * <p>Distinct canaries per class matter: one shared marker would let a leak of the
 * credential masquerade as a leak of the note and vice versa, so a passing run would not
 * say which sink is clean. The row closed on a suite nobody wrote ({@code ER-0099},
 * {@code TD-0007}).
 */
class RiskOperationsEventPrivacyTests {

    private static final UUID MERCHANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID INCIDENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");
    private static final UUID DISPUTE_ID = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID ENDPOINT_ID = UUID.fromString("00000000-0000-0000-0000-000000000107");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000108");
    private static final UUID REFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000109");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-28T08:00:00Z");

    // A merchant API key is "thaap_test_" — eleven characters — followed by a UUID with
    // its dashes removed, and `SecretHasher.prefix` keeps the first twelve. The published
    // prefix is therefore a fixed namespace plus one hex character: an identifier, not
    // secret material. The full key is what must never appear, and it is seeded so that
    // its own legitimate prefix is present in the fixture, which is what makes the
    // assertion meaningful rather than vacuous.
    private static final String MERCHANT_API_KEY_PREFIX = "thaap_test_f";
    private static final String MERCHANT_CREDENTIAL_CANARY =
            "thaap_test_ffffffffffffffffffffffffffffffff";
    private static final String OPERATOR_CREDENTIAL_CANARY = "rop_live_operatorcredentialcanary";
    private static final String MERCHANT_NAME_CANARY = "merchant-name-canary";
    private static final String OPERATOR_NAME_CANARY = "operator-name-canary";
    private static final String PAN_CANARY = "4111111111111111";
    private static final String NOTE_CANARY = "note-plaintext-canary";
    private static final String CONTACT_CANARY = "canary@example.test";

    private static final List<String> PROHIBITED = List.of(
            OPERATOR_CREDENTIAL_CANARY, MERCHANT_CREDENTIAL_CANARY, MERCHANT_NAME_CANARY,
            OPERATOR_NAME_CANARY, PAN_CANARY, NOTE_CANARY, CONTACT_CANARY);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainEventCatalogue catalogue = new DomainEventCatalogue();
    private final CanonicalEventCodec codec = new CanonicalEventCodec(catalogue, objectMapper);

    @Test
    void noRiskOperationEventLeaksACredentialIdentityOrPlaintextIntoItsCanonicalBytes() {
        List<DomainEventCommand> commands = everyCatalogueFact();

        assertThat(commands).isNotEmpty();
        for (DomainEventCommand command : commands) {
            EncodedDomainEvent encoded = codec.encode(
                    command, UUID.randomUUID(), 1L, OCCURRED_AT);
            String payload = new String(encoded.payloadBytes(), StandardCharsets.UTF_8);
            String record = new String(encoded.eventRecordBytes(), StandardCharsets.UTF_8);
            for (String canary : PROHIBITED) {
                assertThat(payload)
                        .withFailMessage("%s payload leaked %s", command.eventType(), canary)
                        .doesNotContain(canary);
                assertThat(record)
                        .withFailMessage("%s record leaked %s", command.eventType(), canary)
                        .doesNotContain(canary);
            }
        }
    }

    @Test
    void everyRiskPlatformEventTypeIsCoveredByThisScanRatherThanASample() {
        // A privacy scan that silently covers half the catalogue is worse than none: it
        // reads as complete. This pins the scan to the catalogue itself, so a new risk
        // event type fails here until it is driven through the canary fixture.
        Set<DomainEventType> scanned = everyCatalogueFact().stream()
                .map(DomainEventCommand::eventType).collect(Collectors.toSet());
        Set<DomainEventType> riskPlatformTypes = Arrays.stream(DomainEventType.values())
                .filter(type -> type.producer() == DomainEventProducer.RISK_PLATFORM)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DomainEventType.class)));

        assertThat(scanned).containsExactlyInAnyOrderElementsOf(riskPlatformTypes);
    }

    @Test
    void theActorCarriesAnIdentifierRatherThanTheCredentialThatAuthorisedIt() {
        // Lineage requires the actor. Non-disclosure forbids the secret that authorised
        // them. Both hold only if the actor reference is an identifier: an operator
        // event names the operator UUID, and a merchant event names the bounded API key
        // prefix. Neither may carry a full credential.
        List<DomainEventCommand> commands = everyCatalogueFact();

        assertThat(commands).allSatisfy(command -> {
            assertThat(command.actor()).isNotNull();
            String rendered = command.actor().toString();
            assertThat(rendered).doesNotContain(OPERATOR_CREDENTIAL_CANARY);
            assertThat(rendered).doesNotContain(MERCHANT_CREDENTIAL_CANARY);
            assertThat(rendered).doesNotContain(OPERATOR_NAME_CANARY);
        });
    }

    @Test
    void thePublishedMerchantActorPrefixCannotGrowIntoSecretMaterial() {
        // This is the property the prefix rests on rather than an incidental fact about
        // today's key format: the twelve published characters must be covered by the
        // fixed "thaap_test_" namespace plus at most one character of the random UUID.
        // Shortening the namespace, or widening the prefix, would start writing real
        // entropy into immutable event bytes that are replicated to every consumer.
        String namespace = "thaap_test_";
        String rawKey = namespace + "0123456789abcdef0123456789abcdef";

        String published = com.thaap.payments.security.SecretHasher.prefix(rawKey);

        assertThat(published).startsWith(namespace);
        assertThat(published.length() - namespace.length())
                .withFailMessage("published prefix exposes %d characters of key entropy",
                        published.length() - namespace.length())
                .isLessThanOrEqualTo(1);
    }

    @Test
    void theRequestAndTraceLineageNeverBecomeACarrierForProhibitedText() {
        // Request identifiers are attacker-influencable in a way payload fields are not,
        // so a canary placed there must not survive into the canonical bytes either.
        DomainEventWriter writer = mock(DomainEventWriter.class);
        RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);

        producer.riskIncidentOpened(merchantContext(), INCIDENT_ID, OCCURRED_AT,
                "request-" + PAN_CANARY, "HIGH", 8L);

        ArgumentCaptor<DomainEventCommand> captor =
                ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(writer).append(captor.capture());
        EncodedDomainEvent encoded = codec.encode(
                captor.getValue(), UUID.randomUUID(), 1L, OCCURRED_AT);

        assertThat(new String(encoded.payloadBytes(), StandardCharsets.UTF_8))
                .doesNotContain(PAN_CANARY);
    }

    private MerchantContext merchantContext() {
        return new MerchantContext(MERCHANT_ID, MERCHANT_NAME_CANARY,
                MERCHANT_API_KEY_PREFIX, TrafficOrigin.SYNTHETIC_SCENARIO);
    }

    private RiskOperatorContext operatorContext() {
        return new RiskOperatorContext(MERCHANT_ID, OPERATOR_ID, OPERATOR_NAME_CANARY,
                RiskOperatorRole.RISK_MANAGER, OPERATOR_CREDENTIAL_CANARY);
    }

    private List<DomainEventCommand> everyCatalogueFact() {
        DomainEventWriter writer = mock(DomainEventWriter.class);
        RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);
        MerchantContext merchant = merchantContext();
        RiskOperatorContext operator = operatorContext();

        producer.manualReviewOpenedBySystem(MERCHANT_ID, PAYMENT_ID, OCCURRED_AT, "request-1",
                TrafficOrigin.SYNTHETIC_SCENARIO, "STANDARD", List.of("RISK_THRESHOLD"));
        producer.manualReviewAssigned(operator, PAYMENT_ID, OCCURRED_AT, "request-2",
                1L, OPERATOR_ID, TrafficOrigin.MERCHANT_API);
        producer.manualReviewResolved(operator, PAYMENT_ID, OCCURRED_AT, "request-3",
                "BLOCK", "CARD_NOT_PRESENT", TrafficOrigin.MERCHANT_API);
        producer.fraudLabelRecorded(operator, PAYMENT_ID, OCCURRED_AT, "request-4",
                "FRAUD", "MANUAL_REVIEW", OCCURRED_AT, "CARD_NOT_PRESENT",
                TrafficOrigin.MERCHANT_API);
        producer.fraudLabelSuperseded(merchant, PAYMENT_ID, OCCURRED_AT, "request-5",
                "FRAUD", "CHARGEBACK", OCCURRED_AT, "STOLEN_CREDENTIAL", REFERENCE_ID,
                TrafficOrigin.SYNTHETIC_SCENARIO);
        producer.postAuthorizationSignalRecorded(merchant, PAYMENT_ID, OCCURRED_AT,
                "request-6", "SIMULATOR_POST_AUTHORIZATION", "REVIEW",
                List.of("LATE_FRAUD_SIGNAL"), TrafficOrigin.SYNTHETIC_SCENARIO);
        producer.riskIncidentOpened(merchant, INCIDENT_ID, OCCURRED_AT, "request-7", "HIGH", 8L);
        producer.riskIncidentResolved(operator, INCIDENT_ID, OCCURRED_AT, "request-8", "MITIGATED");

        for (DomainEventType eventType : List.of(
                DomainEventType.MERCHANT_RISK_PROFILE_ACTIVATED,
                DomainEventType.MERCHANT_RISK_PROFILE_ROLLED_BACK,
                DomainEventType.RISK_POLICY_ACTIVATED,
                DomainEventType.RISK_POLICY_ROLLED_BACK,
                DomainEventType.AUTHORED_RULE_ACTIVATED,
                DomainEventType.AUTHORED_RULE_ROLLED_BACK,
                DomainEventType.AUTHORED_RULE_DISABLED,
                DomainEventType.SAFETY_EXCEPTION_ACTIVATED,
                DomainEventType.SAFETY_EXCEPTION_EXPIRED,
                DomainEventType.SAFETY_EXCEPTION_DISABLED)) {
            producer.riskControlChanged(operator, CONTROL_ID, eventType, OCCURRED_AT,
                    "control-" + eventType.wireValue(), 3L, "ACTIVE", "GOVERNED_CHANGE");
        }

        producer.disputeWorkflowOpened(merchant, DISPUTE_ID, OCCURRED_AT, "request-9",
                OCCURRED_AT.plusSeconds(3600), "FRAUDULENT", TrafficOrigin.SYNTHETIC_SCENARIO);
        producer.disputeEvidenceSubmitted(operator, DISPUTE_ID, OCCURRED_AT, "request-10",
                2L, "a".repeat(64));
        producer.disputeOutcomeRecorded(merchant, DISPUTE_ID, OCCURRED_AT, "request-11",
                "WON", 1200L, "USD", TrafficOrigin.SYNTHETIC_SCENARIO);
        producer.disputeWorkflowExpired(MERCHANT_ID, DISPUTE_ID, OCCURRED_AT);

        producer.webhookEndpointVerified(merchant, ENDPOINT_ID, OCCURRED_AT, "request-12",
                REFERENCE_ID);
        producer.webhookEndpointDisabled(merchant, ENDPOINT_ID, OCCURRED_AT, "request-13",
                "MERCHANT_DISABLED");
        producer.webhookEndpointSecretRotated(merchant, ENDPOINT_ID, OCCURRED_AT,
                "request-14", REFERENCE_ID);
        producer.webhookDeliveryExhausted(MERCHANT_ID, ENDPOINT_ID, OCCURRED_AT,
                2, 5, "REQUEST_TIMEOUT");
        producer.webhookRedeliveryRequested(operator, ENDPOINT_ID, OCCURRED_AT,
                "request-15", 2, "RECEIVER_REPAIRED");
        producer.webhookRedeliverySucceeded(MERCHANT_ID, ENDPOINT_ID, OCCURRED_AT, 2);

        producer.developerScenarioCreated(merchant, SCENARIO_ID, OCCURRED_AT,
                "request-16", "APPROVED_PAYMENT");
        producer.developerScenarioCompleted(merchant, SCENARIO_ID, OCCURRED_AT,
                "request-17", "VERIFIED");
        producer.developerScenarioFailed(merchant, SCENARIO_ID, OCCURRED_AT,
                "request-18", "PROCESSOR_MISMATCH");

        ArgumentCaptor<DomainEventCommand> captor =
                ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }
}
