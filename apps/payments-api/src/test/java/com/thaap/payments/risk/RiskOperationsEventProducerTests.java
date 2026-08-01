package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventProducer;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RiskOperationsEventProducerTests {

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

    @Test
    void everyRiskPlatformCatalogueFactProducesOneSchemaValidTenantQualifiedCommand() {
        DomainEventWriter writer = mock(DomainEventWriter.class);
        RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);
        MerchantContext merchant = new MerchantContext(MERCHANT_ID, "risk-test", "rk_test",
                TrafficOrigin.SYNTHETIC_SCENARIO);
        RiskOperatorContext operator = new RiskOperatorContext(MERCHANT_ID, OPERATOR_ID,
                "risk-manager", RiskOperatorRole.RISK_MANAGER, "rop_test");

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

        List<DomainEventType> controlTypes = List.of(
                DomainEventType.MERCHANT_RISK_PROFILE_ACTIVATED,
                DomainEventType.MERCHANT_RISK_PROFILE_ROLLED_BACK,
                DomainEventType.RISK_POLICY_ACTIVATED,
                DomainEventType.RISK_POLICY_ROLLED_BACK,
                DomainEventType.AUTHORED_RULE_ACTIVATED,
                DomainEventType.AUTHORED_RULE_ROLLED_BACK,
                DomainEventType.AUTHORED_RULE_DISABLED,
                DomainEventType.SAFETY_EXCEPTION_ACTIVATED,
                DomainEventType.SAFETY_EXCEPTION_EXPIRED,
                DomainEventType.SAFETY_EXCEPTION_DISABLED);
        for (DomainEventType eventType : controlTypes) {
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

        producer.webhookEndpointVerified(merchant, ENDPOINT_ID, OCCURRED_AT, "request-12", REFERENCE_ID);
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

        ArgumentCaptor<DomainEventCommand> captor = ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(writer, org.mockito.Mockito.times(31)).append(captor.capture());
        List<DomainEventCommand> commands = captor.getAllValues();
        Set<DomainEventType> actualTypes = commands.stream()
                .map(DomainEventCommand::eventType).collect(Collectors.toSet());
        Set<DomainEventType> expectedTypes = Arrays.stream(DomainEventType.values())
                .filter(type -> type.producer() == DomainEventProducer.RISK_PLATFORM)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DomainEventType.class)));

        assertThat(actualTypes).containsExactlyInAnyOrderElementsOf(expectedTypes);
        assertThat(commands).allSatisfy(command -> {
            assertThat(command.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(command.streamType()).isEqualTo(command.eventType().streamType());
            assertThat(command.streamId()).isEqualTo(expectedStreamId(command.streamType()));
            assertThat(command.eventVersion()).isEqualTo(DomainEventCatalogue.INITIAL_EVENT_VERSION);
            assertThat(command.correlationId()).isEqualTo(command.streamId().toString());
        });
        assertThat(command(commands, DomainEventType.MANUAL_REVIEW_OPENED).actor().type())
                .isEqualTo(DomainEventActorType.SYSTEM);
        assertThat(command(commands, DomainEventType.RISK_INCIDENT_OPENED).actor().type())
                .isEqualTo(DomainEventActorType.SYSTEM);
        assertThat(command(commands, DomainEventType.AUTHORED_RULE_ACTIVATED).actor().type())
                .isEqualTo(DomainEventActorType.RISK_OPERATOR);
        assertThat(command(commands, DomainEventType.AUTHORED_RULE_ACTIVATED).trafficOrigin())
                .isNull();
        assertThat(command(commands, DomainEventType.DISPUTE_EVIDENCE_SUBMITTED).trafficOrigin())
                .isNull();
        assertThat(command(commands, DomainEventType.WEBHOOK_DELIVERY_EXHAUSTED).actor().type())
                .isEqualTo(DomainEventActorType.SYSTEM);
        assertThat(command(commands, DomainEventType.WEBHOOK_DELIVERY_EXHAUSTED).trafficOrigin())
                .isNull();
        assertThat(command(commands, DomainEventType.DEVELOPER_SCENARIO_COMPLETED).actor().type())
                .isEqualTo(DomainEventActorType.MERCHANT_API_KEY);
        assertThat(command(commands, DomainEventType.DEVELOPER_SCENARIO_COMPLETED).trafficOrigin())
                .isEqualTo(TrafficOrigin.SYNTHETIC_SCENARIO);

        CanonicalEventCodec codec = new CanonicalEventCodec(
                new DomainEventCatalogue(), new ObjectMapper());
        for (int index = 0; index < commands.size(); index++) {
            DomainEventCommand command = commands.get(index);
            codec.encode(command, UUID.randomUUID(), index + 1L, OCCURRED_AT.plusSeconds(1));
            assertThat(command.payload().toString())
                    .doesNotContain("rk_test", "rop_test", "request-");
        }
    }

    @Test
    void riskControlBoundaryRejectsPaymentEventBeforeWriterInvocation() {
        DomainEventWriter writer = mock(DomainEventWriter.class);
        RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);
        RiskOperatorContext operator = new RiskOperatorContext(MERCHANT_ID, OPERATOR_ID,
                "risk-manager", RiskOperatorRole.RISK_MANAGER, "rop_test");

        assertThatThrownBy(() -> producer.riskControlChanged(operator, CONTROL_ID,
                DomainEventType.PAYMENT_INTENT_CREATED, OCCURRED_AT, "request",
                1L, "ACTIVE", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RISK_CONTROL");
        verifyNoInteractions(writer);
    }

    private static DomainEventCommand command(List<DomainEventCommand> commands,
            DomainEventType eventType) {
        return commands.stream().filter(command -> command.eventType() == eventType)
                .findFirst().orElseThrow();
    }

    private static UUID expectedStreamId(DomainStreamType streamType) {
        return switch (streamType) {
            case PAYMENT -> PAYMENT_ID;
            case RISK_CONTROL -> CONTROL_ID;
            case RISK_INCIDENT -> INCIDENT_ID;
            case DISPUTE -> DISPUTE_ID;
            case WEBHOOK_ENDPOINT -> ENDPOINT_ID;
            case DEVELOPER_SCENARIO -> SCENARIO_ID;
        };
    }
}
