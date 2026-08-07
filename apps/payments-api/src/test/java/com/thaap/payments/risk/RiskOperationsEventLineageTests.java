package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Proof for {@code C46-DATA-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p>The row is about what an operational event preserves of its source: point-in-time
 * availability rather than occurrence, server-owned traffic origin, supersession rather than
 * silent replacement, and actor lineage only where a real actor exists. All four are decided at
 * the producer boundary, which is where they are asserted — the same level at which
 * {@code C46-SEC-001} was paid.
 *
 * <p>What is not proven here: mixed-control reconstruction and run-owned scenario report
 * reconciliation, which the row's evidence line also names, and which depend on reading historical
 * control and simulation state rather than on the producer's mapping.
 */
class RiskOperationsEventLineageTests {

    private static final UUID MERCHANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final UUID SUPERSEDED_LABEL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000304");

    // Deliberately far apart. A label's evidence occurs when the chargeback lands; it becomes
    // available for training only after the dispute window. Collapsing the two is the leak this
    // row exists to prevent, and equal timestamps would let a substitution pass unnoticed.
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-01T08:00:00Z");
    private static final Instant AVAILABLE_AT = Instant.parse("2026-08-15T08:00:00Z");

    private final DomainEventWriter writer = mock(DomainEventWriter.class);
    private final RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);
    private final RiskOperatorContext context = new RiskOperatorContext(MERCHANT_ID, OPERATOR_ID,
            "rop_lineage_code", RiskOperatorRole.RISK_MANAGER, "rop_live_a");

    /**
     * Availability and occurrence are different facts and must both survive. A dataset that reads
     * occurrence as availability trains on labels that did not exist at decision time, which is
     * the exact leak the offline-replay point-in-time rows depend on not happening upstream.
     */
    @Test
    void aFraudLabelCarriesAvailabilitySeparatelyFromOccurrence() {
        producer.fraudLabelRecorded(context, PAYMENT_ID, OCCURRED_AT, "lineage-request",
                "FRAUD", "MANUAL_REVIEW", AVAILABLE_AT, "STOLEN_PAYMENT_CREDENTIAL",
                TrafficOrigin.MERCHANT_API);

        DomainEventCommand command = captured();
        assertThat(command.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(command.payload().get("availableAt").asText())
                .isEqualTo(AVAILABLE_AT.toString());
        assertThat(command.payload().get("availableAt").asText())
                .describedAs("availability must not collapse onto occurrence")
                .isNotEqualTo(OCCURRED_AT.toString());
        assertThat(command.payload().get("label").asText()).isEqualTo("FRAUD");
        assertThat(command.payload().get("source").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(command.payload().get("fraudType").asText())
                .isEqualTo("STOLEN_PAYMENT_CREDENTIAL");
    }

    /**
     * A contradicting label supersedes rather than replaces, and the event says which label it
     * supersedes. Without the reference a reader cannot tell a correction from a second
     * independent label, and would count the same attempt twice.
     */
    @Test
    void aSupersedingLabelNamesTheLabelItSupersedes() {
        producer.fraudLabelSuperseded(context, PAYMENT_ID, OCCURRED_AT, "lineage-supersede",
                "LEGITIMATE", "MANUAL_REVIEW", AVAILABLE_AT, null, SUPERSEDED_LABEL_ID,
                TrafficOrigin.MERCHANT_API);

        DomainEventCommand command = captured();
        assertThat(command.eventType().name()).isEqualTo("FRAUD_LABEL_SUPERSEDED");
        assertThat(command.payload().get("supersedesLabelReference").asText())
                .isEqualTo(SUPERSEDED_LABEL_ID.toString());
        assertThat(command.payload().get("label").asText()).isEqualTo("LEGITIMATE");
        // The optional fraud type is absent rather than empty when the label is not fraud.
        assertThat(command.payload().has("fraudType")).isFalse();
    }

    /**
     * A recorded label that supersedes nothing must not carry a supersession reference at all.
     * An always-present field with a null-ish value is how a reader learns to ignore it.
     */
    @Test
    void aFirstLabelCarriesNoSupersessionReference() {
        producer.fraudLabelRecorded(context, PAYMENT_ID, OCCURRED_AT, "lineage-first",
                "FRAUD", "MANUAL_REVIEW", AVAILABLE_AT, "CARD_TESTING",
                TrafficOrigin.MERCHANT_API);

        assertThat(captured().payload().has("supersedesLabelReference")).isFalse();
    }

    /**
     * Traffic origin is server-owned and travels with the fact. Synthetic scenario traffic must
     * stay distinguishable from merchant API traffic, or a simulated outcome becomes a network
     * settlement in every downstream aggregate.
     */
    @Test
    void trafficOriginIsCarriedFromTheServerAndSeparatesSyntheticFromLiveTraffic() {
        producer.fraudLabelRecorded(context, PAYMENT_ID, OCCURRED_AT, "lineage-live",
                "FRAUD", "MANUAL_REVIEW", AVAILABLE_AT, "CARD_TESTING",
                TrafficOrigin.MERCHANT_API);
        assertThat(captured().trafficOrigin()).isEqualTo(TrafficOrigin.MERCHANT_API);

        DomainEventWriter syntheticWriter = mock(DomainEventWriter.class);
        new RiskOperationsEventProducer(syntheticWriter).fraudLabelRecorded(context, PAYMENT_ID,
                OCCURRED_AT, "lineage-synthetic", "FRAUD", "MANUAL_REVIEW", AVAILABLE_AT,
                "CARD_TESTING", TrafficOrigin.SYNTHETIC_SCENARIO);
        ArgumentCaptor<DomainEventCommand> synthetic =
                ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(syntheticWriter).append(synthetic.capture());
        assertThat(synthetic.getValue().trafficOrigin())
                .isEqualTo(TrafficOrigin.SYNTHETIC_SCENARIO);

        // Origin is a typed field on the command, never a payload value a caller could restate
        // and disagree with.
        assertThat(synthetic.getValue().payload().has("trafficOrigin")).isFalse();
    }

    /**
     * Request lineage is preserved where it is real. The correlation is the stream the fact
     * belongs to, so a reader can reconstruct which aggregate a request touched.
     */
    @Test
    void requestAndCorrelationLineageArePreservedAgainstTheOwningStream() {
        producer.manualReviewResolved(context, PAYMENT_ID, OCCURRED_AT, "lineage-resolve-request",
                "CONFIRMED_FRAUD", "ACCOUNT_TAKEOVER", TrafficOrigin.MERCHANT_API);

        DomainEventCommand command = captured();
        assertThat(command.requestId()).isEqualTo("lineage-resolve-request");
        assertThat(command.correlationId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(command.streamId()).isEqualTo(PAYMENT_ID);
        assertThat(command.actor().type()).isEqualTo(DomainEventActorType.RISK_OPERATOR);
        assertThat(command.actor().id()).isEqualTo(OPERATOR_ID.toString());
    }

    /**
     * An automatic transition has no operator, and the lineage says so rather than borrowing one.
     * A system actor is a real answer; an absent one would be a hole a reader has to guess about.
     */
    @Test
    void anAutomaticTransitionRecordsSystemLineageRatherThanAnAbsentActor() {
        producer.disputeWorkflowExpired(MERCHANT_ID, UUID.randomUUID(),
                OCCURRED_AT.plus(1, ChronoUnit.DAYS));

        DomainEventCommand command = captured();
        assertThat(command.actor().type()).isEqualTo(DomainEventActorType.SYSTEM);
        assertThat(command.actor().id()).isEqualTo("thaap-risk-operations");
        assertThat(command.actor().id()).doesNotContain(OPERATOR_ID.toString());
    }

    private DomainEventCommand captured() {
        ArgumentCaptor<DomainEventCommand> command =
                ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(writer).append(command.capture());
        return command.getValue();
    }
}
