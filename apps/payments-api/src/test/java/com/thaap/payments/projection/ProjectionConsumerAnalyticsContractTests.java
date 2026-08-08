package com.thaap.payments.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.analytics.AnalyticsFreshnessState;
import com.thaap.payments.analytics.AnalyticsCursorCodec;
import com.thaap.payments.analytics.RiskAnalyticsService;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.event.DomainEvent;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.EncodedDomainEvent;
import com.thaap.payments.eventpublication.DomainEventKafkaHeaders;
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest
class ProjectionConsumerAnalyticsContractTests {

    private static final String PLATFORM_BOOTSTRAP_TOKEN =
            "test-platform-bootstrap-token";

    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService consumerService;
    @Autowired private PaymentRiskProjectionMetrics projectionMetrics;
    @Autowired private QuarantineRecoveryService recoveryService;
    @Autowired private PlatformOperatorService platformOperatorService;
    @Autowired private RiskAnalyticsService analyticsService;
    @Autowired private AnalyticsCursorCodec analyticsCursorCodec;
    @Autowired private CanonicalEventCodec canonicalEventCodec;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private long nextOffset;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        transaction = new TransactionTemplate(transactionManager);
        nextOffset = 0;
    }

    @Test
    void duplicateDeliveryProducesOnePaymentAndMerchantEffectAndTenantBoundCursor() {
        MerchantFixture owner = merchant("analytics-owner");
        PaymentFixture first = payment(owner, "USD", 1_250, TrafficOrigin.MERCHANT_API);
        PaymentFixture second = payment(owner, "EUR", 2_500, TrafficOrigin.MERCHANT_API);
        DomainEvent firstCreated = append(first, DomainEventType.PAYMENT_INTENT_CREATED,
                first.createdAt(), createdPayload(first));
        DomainEvent secondCreated = append(second, DomainEventType.PAYMENT_INTENT_CREATED,
                second.createdAt(), createdPayload(second));

        TransportedDomainEvent firstDelivery = transported(firstCreated, nextOffset++);
        assertThat(consumerService.process(firstDelivery))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        assertThat(consumerService.process(firstDelivery))
                .isEqualTo(ProjectionProcessingOutcome.DUPLICATE);
        assertThat(consumerService.process(transported(secondCreated, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);

        assertThat(count("consumer_inbox", owner.merchant().getId())).isEqualTo(2);
        assertThat(count("payment_risk_projections", owner.merchant().getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT SUM(payment_count) FROM merchant_risk_aggregates
                WHERE merchant_id = ? AND interval_type = 'HOUR'
                  AND traffic_origin_policy = 'ALL'
                """, Long.class, owner.merchant().getId())).isEqualTo(2L);

        RiskOperatorContext ownerContext = context(owner.merchant().getId());
        RiskAnalyticsService.PaymentSearchPage firstPage = analyticsService.searchPayments(
                ownerContext, 1, null, null, null, null);
        assertThat(firstPage.data()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(firstPage.freshness().state()).isEqualTo(AnalyticsFreshnessState.FRESH);
        RiskAnalyticsService.ProjectionStatusView status = analyticsService.projectionStatus(
                ownerContext);
        assertThat(status.paymentRisk().state()).isEqualTo(AnalyticsFreshnessState.FRESH);
        assertThat(status.merchantRisk().state()).isEqualTo(AnalyticsFreshnessState.FRESH);
        assertThat(status.paymentRisk().appliedEventCount()).isEqualTo(2);
        assertThat(status.merchantRisk().appliedEventCount()).isEqualTo(2);

        MerchantFixture foreign = merchant("analytics-foreign");
        assertThatThrownBy(() -> analyticsService.paymentDetail(
                context(foreign.merchant().getId()), first.intent().getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> analyticsService.searchPayments(
                context(foreign.merchant().getId()), 1, firstPage.nextCursor(),
                null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> analyticsService.searchPayments(ownerContext, 1,
                firstPage.nextCursor(), "BLOCK", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> analyticsService.searchPayments(ownerContext, 10,
                null, "UNREGISTERED_ACTION", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        String staleGenerationCursor = analyticsCursorCodec.encode(
                owner.merchant().getId(), UUID.randomUUID(),
                "payments|fraudAction=-|reviewStatus=-|labelValue=-",
                firstCreated.getRecordedAt(), first.intent().getId());
        assertThatThrownBy(() -> analyticsService.searchPayments(ownerContext, 1,
                staleGenerationCursor, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void labelAvailabilityUpdatesOriginalCohortAndOriginPoliciesRemainExplicit() {
        MerchantFixture owner = merchant("analytics-labels");
        Instant intentOccurredAt = DatabaseInstant.now().minus(3, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MICROS);
        PaymentFixture live = payment(owner, "USD", 5_000,
                TrafficOrigin.MERCHANT_API, intentOccurredAt);
        DomainEvent created = append(live, DomainEventType.PAYMENT_INTENT_CREATED,
                intentOccurredAt, createdPayload(live));
        consumerService.process(transported(created, nextOffset++));

        RiskAnalyticsService.PaymentDetail beforeLabel = analyticsService.paymentDetail(
                context(owner.merchant().getId()), live.intent().getId());
        assertThat(beforeLabel.data().labelValue()).isNull();
        assertThat(cohortValue(owner.merchant().getId(), intentOccurredAt,
                "unlabeled_count")).isEqualTo(1L);

        Instant firstAvailableAt = intentOccurredAt.plus(90, ChronoUnit.MINUTES);
        DomainEvent recorded = append(live, DomainEventType.FRAUD_LABEL_RECORDED,
                firstAvailableAt, labelPayload("FRAUD", firstAvailableAt, false));
        consumerService.process(transported(recorded, nextOffset++));
        assertThat(analyticsService.paymentDetail(context(owner.merchant().getId()),
                live.intent().getId()).data().labelAvailableAt()).isEqualTo(firstAvailableAt);
        assertThat(cohortValue(owner.merchant().getId(), intentOccurredAt,
                "unlabeled_count")).isZero();
        assertThat(cohortValue(owner.merchant().getId(), intentOccurredAt,
                "confirmed_fraud_count")).isEqualTo(1L);

        Instant supersededAt = firstAvailableAt.plusSeconds(1);
        DomainEvent superseded = append(live, DomainEventType.FRAUD_LABEL_SUPERSEDED,
                supersededAt, labelPayload("LEGITIMATE", supersededAt, true));
        consumerService.process(transported(superseded, nextOffset++));
        assertThat(cohortValue(owner.merchant().getId(), intentOccurredAt,
                "unlabeled_count")).isZero();
        assertThat(cohortValue(owner.merchant().getId(), intentOccurredAt,
                "confirmed_fraud_count")).isZero();
        RiskAnalyticsService.PaymentDetail afterLabel = analyticsService.paymentDetail(
                context(owner.merchant().getId()), live.intent().getId());
        assertThat(afterLabel.data().labelValue()).isEqualTo("LEGITIMATE");
        assertThat(afterLabel.data().labelAvailableAt()).isEqualTo(supersededAt);

        PaymentFixture synthetic = payment(owner, "EUR", 7_500,
                TrafficOrigin.SYNTHETIC_SCENARIO);
        DomainEvent syntheticCreated = append(synthetic,
                DomainEventType.PAYMENT_INTENT_CREATED, synthetic.createdAt(),
                createdPayload(synthetic));
        consumerService.process(transported(syntheticCreated, nextOffset++));
        DomainEvent incident = append(owner, UUID.randomUUID(), DomainStreamType.RISK_INCIDENT,
                DomainEventType.RISK_INCIDENT_OPENED, DatabaseInstant.now().minusSeconds(1),
                incidentPayload(), TrafficOrigin.MERCHANT_API);
        consumerService.process(transported(incident, nextOffset++));

        RiskAnalyticsService.MerchantSummary liveSummary = analyticsService.merchantSummary(
                context(owner.merchant().getId()), null, null, "LIVE_ONLY");
        RiskAnalyticsService.MerchantSummary syntheticSummary = analyticsService.merchantSummary(
                context(owner.merchant().getId()), null, null, "SYNTHETIC_ONLY");
        assertThat(liveSummary.currencies()).filteredOn(value -> "USD".equals(value.currency()))
                .singleElement().satisfies(value -> assertThat(value.paymentCount()).isOne());
        assertThat(liveSummary.currencies()).filteredOn(value -> "XXX".equals(value.currency()))
                .singleElement().satisfies(value -> {
                    assertThat(value.enumerationSignalCount()).isOne();
                    assertThat(value.intentAmountMinor()).isZero();
                    assertThat(value.capturedAmountMinor()).isZero();
                });
        assertThat(syntheticSummary.currencies())
                .singleElement().satisfies(value -> {
                    assertThat(value.currency()).isEqualTo("EUR");
                    assertThat(value.paymentCount()).isOne();
                });
        assertThat(liveSummary.freshness().eligibleEventCount()).isEqualTo(4);
        assertThat(syntheticSummary.freshness().eligibleEventCount()).isEqualTo(1);
    }

    @Test
    void heldStreamRecoveryDoesNotBlockAnotherStreamOrMutateSourceMoneyTruth() {
        MerchantFixture owner = merchant("analytics-recovery");
        PaymentFixture delayed = payment(owner, "USD", 9_000, TrafficOrigin.MERCHANT_API);
        PaymentFixture unrelated = payment(owner, "GBP", 3_000, TrafficOrigin.MERCHANT_API);
        DomainEvent delayedCreated = append(delayed, DomainEventType.PAYMENT_INTENT_CREATED,
                delayed.createdAt(), createdPayload(delayed));
        DomainEvent delayedCapture = append(delayed, DomainEventType.CAPTURE_CREATED,
                delayed.createdAt().plusSeconds(1), moneyPayload(9_000, "USD"));
        DomainEvent unrelatedCreated = append(unrelated,
                DomainEventType.PAYMENT_INTENT_CREATED, unrelated.createdAt(),
                createdPayload(unrelated));

        assertThat(consumerService.process(transported(delayedCapture, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.HELD);
        assertThat(consumerService.process(transported(unrelatedCreated, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        assertThat(consumerService.process(transported(delayedCreated, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT captured_amount_minor FROM payment_risk_projections
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, Long.class, owner.merchant().getId(), delayed.intent().getId())).isZero();

        PlatformOperatorService.MutationResult admin = platformOperatorService.bootstrap(
                PLATFORM_BOOTSTRAP_TOKEN, "projection-recovery-admin", "Recovery admin",
                "projection-recovery-admin-request");
        PlatformOperatorService.MutationResult operator = platformOperatorService.create(
                admin.credential(), "projection-recovery-data", "Recovery data operator",
                PlatformOperatorRole.DATA_OPERATOR, "projection-recovery-data-request");
        UUID quarantineId = jdbcTemplate.queryForObject("""
                SELECT id FROM consumer_quarantine
                WHERE merchant_id = ? AND domain_event_id = ?
                """, UUID.class, owner.merchant().getId(), delayedCapture.getId());
        QuarantineRecoveryService.RecoveryResult recovered = recoveryService.retry(
                operator.credential(), "recover-held-capture", quarantineId,
                "recover-held-capture-request");
        QuarantineRecoveryService.RecoveryResult replay = recoveryService.retry(
                operator.credential(), "recover-held-capture", quarantineId,
                "recover-held-capture-replay");

        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.recoveredCount()).isOne();
        assertThat(recovered.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT captured_amount_minor FROM payment_risk_projections
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, Long.class, owner.merchant().getId(), delayed.intent().getId()))
                .isEqualTo(9_000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_stream_positions
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                  AND last_stream_version = 2
                """, Integer.class, owner.merchant().getId(), delayed.intent().getId()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?", String.class,
                delayed.intent().getId())).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries", Integer.class)).isZero();
    }

    @Test
    void tenantKeyDigestAndSizeAttacksClosePayloadFreeQuarantines() {
        MerchantFixture owner = merchant("analytics-attacks");
        MerchantFixture foreign = merchant("analytics-attacks-foreign");
        PaymentFixture keyPayment = payment(owner, "USD", 100, TrafficOrigin.MERCHANT_API);
        DomainEvent keyEvent = append(keyPayment, DomainEventType.PAYMENT_INTENT_CREATED,
                keyPayment.createdAt(), createdPayload(keyPayment));
        TransportedDomainEvent correct = transported(keyEvent, nextOffset++);
        TransportedDomainEvent wrongKey = new TransportedDomainEvent(correct.topic(),
                correct.partition(), correct.offset(), "foreign-key".getBytes(StandardCharsets.US_ASCII),
                correct.eventRecordBytes(), correct.sourceEventId(), correct.sourceMerchantId(),
                correct.eventRecordSha256());
        assertThat(consumerService.process(wrongKey))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);

        PaymentFixture tenantPayment = payment(owner, "USD", 200, TrafficOrigin.MERCHANT_API);
        DomainEvent tenantEvent = append(tenantPayment, DomainEventType.PAYMENT_INTENT_CREATED,
                tenantPayment.createdAt(), createdPayload(tenantPayment));
        TransportedDomainEvent tenantCorrect = transported(tenantEvent, nextOffset++);
        TransportedDomainEvent wrongTenant = new TransportedDomainEvent(tenantCorrect.topic(),
                tenantCorrect.partition(), tenantCorrect.offset(), tenantCorrect.recordKey(),
                tenantCorrect.eventRecordBytes(), tenantCorrect.sourceEventId(),
                foreign.merchant().getId(), tenantCorrect.eventRecordSha256());
        assertThat(consumerService.process(wrongTenant))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);

        PaymentFixture digestPayment = payment(owner, "USD", 300, TrafficOrigin.MERCHANT_API);
        DomainEvent digestEvent = append(digestPayment, DomainEventType.PAYMENT_INTENT_CREATED,
                digestPayment.createdAt(), createdPayload(digestPayment));
        TransportedDomainEvent digestCorrect = transported(digestEvent, nextOffset++);
        TransportedDomainEvent wrongDigest = new TransportedDomainEvent(digestCorrect.topic(),
                digestCorrect.partition(), digestCorrect.offset(), digestCorrect.recordKey(),
                digestCorrect.eventRecordBytes(), digestCorrect.sourceEventId(),
                digestCorrect.sourceMerchantId(), "f".repeat(64));
        assertThat(consumerService.process(wrongDigest))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);

        PaymentFixture sizePayment = payment(owner, "USD", 400, TrafficOrigin.MERCHANT_API);
        DomainEvent sizeEvent = append(sizePayment, DomainEventType.PAYMENT_INTENT_CREATED,
                sizePayment.createdAt(), createdPayload(sizePayment));
        TransportedDomainEvent sizeCorrect = transported(sizeEvent, nextOffset++);
        TransportedDomainEvent oversized = new TransportedDomainEvent(sizeCorrect.topic(),
                sizeCorrect.partition(), sizeCorrect.offset(), sizeCorrect.recordKey(),
                new byte[65_537], sizeCorrect.sourceEventId(), sizeCorrect.sourceMerchantId(),
                sizeCorrect.eventRecordSha256());
        assertThat(consumerService.process(oversized))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);

        ObjectNode alteredPayload = createdPayload(keyPayment);
        alteredPayload.put("amountMinor", keyPayment.amount() + 1);
        EncodedDomainEvent altered = canonicalEventCodec.encode(
                eventCommand(keyPayment, DomainEventType.PAYMENT_INTENT_CREATED,
                        keyEvent.getOccurredAt(), alteredPayload),
                keyEvent.getId(), keyEvent.getStreamVersion(), keyEvent.getRecordedAt());
        TransportedDomainEvent alteredSameIdentity = new TransportedDomainEvent(
                correct.topic(), correct.partition(), nextOffset++, correct.recordKey(),
                altered.eventRecordBytes(), correct.sourceEventId(),
                correct.sourceMerchantId(), altered.eventRecordSha256());
        assertThat(consumerService.process(alteredSameIdentity))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);

        TransportedDomainEvent malformedWithVictimHeaders = new TransportedDomainEvent(
                correct.topic(), correct.partition(), nextOffset++, correct.recordKey(),
                "{}".getBytes(StandardCharsets.US_ASCII), correct.sourceEventId(),
                foreign.merchant().getId(), correct.eventRecordSha256());
        assertThat(consumerService.process(malformedWithVictimHeaders))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);

        assertThat(consumerService.process(transported(keyEvent, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);

        assertThat(jdbcTemplate.queryForList("""
                SELECT reason_code FROM consumer_transport_quarantine ORDER BY reason_code
                """, String.class)).containsExactlyInAnyOrder(
                        "RECORD_KEY_MISMATCH", "TENANT_STREAM_DISAGREEMENT",
                        "RECORD_DIGEST_MISMATCH", "PAYLOAD_LIMIT_EXCEEDED",
                        "SOURCE_IDENTITY_CONFLICT", "INVALID_ENVELOPE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'consumer_transport_quarantine'
                  AND column_name IN ('payload', 'payload_bytes', 'exception', 'error_message')
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_quarantine", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox", Integer.class)).isOne();
        assertThat(count("payment_risk_projections", owner.merchant().getId())).isOne();
        assertThat(count("consumer_inbox", foreign.merchant().getId())).isZero();
    }

    @Test
    void acknowledgementFailureAfterCommitRedeliversAsInboxNoOp() {
        MerchantFixture owner = merchant("analytics-ack-ambiguity");
        PaymentFixture payment = payment(owner, "USD", 1_100, TrafficOrigin.MERCHANT_API);
        DomainEvent event = append(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.createdAt(), createdPayload(payment));
        ConsumerRecord<byte[], byte[]> record = kafkaRecord(event, nextOffset++);
        PaymentRiskProjectionKafkaListener listener = new PaymentRiskProjectionKafkaListener(
                consumerService, projectionMetrics);
        Acknowledgment lostAcknowledgement = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("test acknowledgement ambiguity"))
                .when(lostAcknowledgement).acknowledge();

        assertThatThrownBy(() -> listener.receive(record, lostAcknowledgement))
                .isInstanceOf(IllegalStateException.class);
        Acknowledgment recoveredAcknowledgement = mock(Acknowledgment.class);
        listener.receive(record, recoveredAcknowledgement);

        verify(recoveredAcknowledgement).acknowledge();
        assertThat(count("consumer_inbox", owner.merchant().getId())).isOne();
        assertThat(count("payment_risk_projections", owner.merchant().getId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT SUM(payment_count) FROM merchant_risk_aggregates
                WHERE merchant_id = ? AND interval_type = 'HOUR'
                  AND traffic_origin_policy = 'ALL'
                """, Long.class, owner.merchant().getId())).isOne();
    }

    @Test
    void transportQuarantineInventoryIsSafeImmutableAndNonRecoverable() {
        ConsumerRecord<byte[], byte[]> malformed = new ConsumerRecord<>(
                PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, nextOffset++,
                "untrusted-key".getBytes(StandardCharsets.US_ASCII),
                "{}".getBytes(StandardCharsets.US_ASCII));
        PaymentRiskProjectionKafkaListener listener = new PaymentRiskProjectionKafkaListener(
                consumerService, projectionMetrics);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.receive(malformed, acknowledgment);

        verify(acknowledgment).acknowledge();
        UUID transportQuarantineId = jdbcTemplate.queryForObject("""
                SELECT id FROM consumer_transport_quarantine
                """, UUID.class);
        PlatformOperatorService.MutationResult admin = platformOperatorService.bootstrap(
                PLATFORM_BOOTSTRAP_TOKEN, "transport-quarantine-admin", "Transport admin",
                "transport-quarantine-admin-request");
        PlatformOperatorService.MutationResult operator = platformOperatorService.create(
                admin.credential(), "transport-quarantine-data", "Transport data operator",
                PlatformOperatorRole.DATA_OPERATOR, "transport-quarantine-data-request");

        assertThat(recoveryService.list(operator.credential(), "OPEN", 10))
                .singleElement().satisfies(view -> {
                    assertThat(view.quarantineId()).isEqualTo(transportQuarantineId);
                    assertThat(view.reasonCode()).isEqualTo("INVALID_ENVELOPE");
                    assertThat(view.recoverable()).isFalse();
                    assertThat(view.eventType()).isNull();
                    assertThat(view.eventVersion()).isNull();
                    assertThat(view.streamType()).isNull();
                });
        assertThatThrownBy(() -> recoveryService.retry(operator.credential(),
                "transport-quarantine-retry", transportQuarantineId,
                "transport-quarantine-retry-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM consumer_transport_quarantine WHERE id = ?
                """, String.class, transportQuarantineId)).isEqualTo("OPEN");
        TransportedDomainEvent exactRedelivery = new TransportedDomainEvent(
                malformed.topic(), malformed.partition(), malformed.offset(),
                malformed.key(), malformed.value(), null, null, null);
        assertThat(consumerService.process(exactRedelivery))
                .isEqualTo(ProjectionProcessingOutcome.DUPLICATE);
    }

    @Test
    void missingImmutableSourceIsRetriedAsDependencyWithoutDurablePoisonState() {
        MerchantFixture owner = merchant("analytics-missing-source");
        PaymentFixture payment = payment(owner, "USD", 2_100, TrafficOrigin.MERCHANT_API);
        UUID missingEventId = UUID.randomUUID();
        DomainEventCommand command = eventCommand(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.createdAt(), createdPayload(payment));
        EncodedDomainEvent encoded = canonicalEventCodec.encode(command, missingEventId, 1,
                payment.createdAt().plusMillis(1));
        TransportedDomainEvent transported = new TransportedDomainEvent(
                PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, nextOffset++,
                TenantQualifiedStreamKey.encode(owner.merchant().getId(),
                        DomainStreamType.PAYMENT, payment.intent().getId()),
                encoded.eventRecordBytes(), missingEventId, owner.merchant().getId(),
                encoded.eventRecordSha256());

        assertThatThrownBy(() -> consumerService.process(transported))
                .isInstanceOf(ProjectionSourceUnavailableException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_transport_quarantine", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_quarantine", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox", Integer.class)).isZero();
    }

    @Test
    void retainedPaymentWithoutEventLineageIsPartialRatherThanFakeOrEmpty() {
        MerchantFixture owner = merchant("analytics-retained-gap");
        payment(owner, "USD", 8_200, TrafficOrigin.MERCHANT_API);
        RiskOperatorContext context = context(owner.merchant().getId());

        RiskAnalyticsService.PaymentSearchPage search = analyticsService.searchPayments(
                context, 10, null, null, null, null);
        RiskAnalyticsService.ProjectionStatusView status = analyticsService.projectionStatus(
                context);

        assertThat(search.data()).isEmpty();
        assertThat(search.freshness().state()).isEqualTo(AnalyticsFreshnessState.PARTIAL);
        assertThat(search.freshness().eligibleEventCount()).isZero();
        assertThat(search.freshness().appliedEventCount()).isZero();
        assertThat(search.freshness().unavailableStreamCount()).isOne();
        assertThat(status.paymentRisk().state()).isEqualTo(AnalyticsFreshnessState.PARTIAL);
        assertThat(status.merchantRisk().state()).isEqualTo(AnalyticsFreshnessState.PARTIAL);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_events", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_risk_projections", Integer.class)).isZero();
    }

    @Test
    void projectionRowsCannotCrossDefinitionOwnershipBoundaries() {
        MerchantFixture owner = merchant("analytics-definition-owner");
        PaymentFixture payment = payment(owner, "USD", 4_400, TrafficOrigin.MERCHANT_API);
        DomainEvent created = append(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.createdAt(), createdPayload(payment));
        assertThat(consumerService.process(transported(created, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        UUID paymentDefinition = UUID.fromString(
                "00000000-0000-0000-0000-000000000901");
        UUID merchantDefinition = UUID.fromString(
                "00000000-0000-0000-0000-000000000902");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payment_risk_projections SET projection_definition_id = ?
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, merchantDefinition, owner.merchant().getId(), payment.intent().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE merchant_risk_aggregates SET projection_definition_id = ?
                WHERE merchant_id = ?
                """, paymentDefinition, owner.merchant().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE merchant_payment_cohort_states SET projection_definition_id = ?
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, paymentDefinition, owner.merchant().getId(), payment.intent().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void analyticsRangesAndEveryCollectionCursorAreClosedAndLossless() {
        MerchantFixture owner = merchant("analytics-pagination");
        Instant intervalStart = DatabaseInstant.now().minus(2, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.HOURS);
        PaymentFixture usd = payment(owner, "USD", 1_000, TrafficOrigin.MERCHANT_API,
                intervalStart.plusSeconds(1));
        PaymentFixture eur = payment(owner, "EUR", 2_000, TrafficOrigin.MERCHANT_API,
                intervalStart.plusSeconds(2));
        consumerService.process(transported(append(usd, DomainEventType.PAYMENT_INTENT_CREATED,
                usd.createdAt(), createdPayload(usd)), nextOffset++));
        consumerService.process(transported(append(eur, DomainEventType.PAYMENT_INTENT_CREATED,
                eur.createdAt(), createdPayload(eur)), nextOffset++));
        RiskOperatorContext ownerContext = context(owner.merchant().getId());
        Instant rangeEnd = intervalStart.plus(1, ChronoUnit.HOURS);

        RiskAnalyticsService.MerchantSummary defaultSummary = analyticsService.merchantSummary(
                ownerContext, null, null, "ALL");
        assertThat(defaultSummary.currencies()).extracting(value -> value.currency())
                .containsExactly("EUR", "USD");
        assertThat(defaultSummary.to()).isAfter(DatabaseInstant.now());

        RiskAnalyticsService.MerchantSummary summaryFirst = analyticsService.merchantSummary(
                ownerContext, intervalStart, rangeEnd, "ALL", 1, null);
        RiskAnalyticsService.MerchantSummary summarySecond = analyticsService.merchantSummary(
                ownerContext, intervalStart, rangeEnd, "ALL", 1, summaryFirst.nextCursor());
        assertThat(summaryFirst.currencies()).extracting(value -> value.currency())
                .containsExactly("EUR");
        assertThat(summaryFirst.nextCursor()).isNotBlank();
        assertThat(summarySecond.currencies()).extracting(value -> value.currency())
                .containsExactly("USD");
        assertThat(summarySecond.nextCursor()).isNull();

        RiskAnalyticsService.MerchantTimeSeries timeFirst = analyticsService.merchantTimeSeries(
                ownerContext, intervalStart, rangeEnd, "HOUR", "ALL", 1, null);
        RiskAnalyticsService.MerchantTimeSeries timeSecond = analyticsService.merchantTimeSeries(
                ownerContext, intervalStart, rangeEnd, "HOUR", "ALL", 1,
                timeFirst.nextCursor());
        assertThat(timeFirst.points()).extracting(value -> value.currency())
                .containsExactly("EUR");
        assertThat(timeFirst.nextCursor()).isNotBlank();
        assertThat(timeSecond.points()).extracting(value -> value.currency())
                .containsExactly("USD");
        assertThat(timeSecond.nextCursor()).isNull();

        RiskAnalyticsService.ProjectionStatusView statusFirst = analyticsService.projectionStatus(
                ownerContext, 1, null);
        RiskAnalyticsService.ProjectionStatusView statusSecond = analyticsService.projectionStatus(
                ownerContext, 1, statusFirst.nextCursor());
        assertThat(statusFirst.generations()).hasSize(1);
        assertThat(statusFirst.nextCursor()).isNotBlank();
        assertThat(statusSecond.generations()).hasSize(1);
        assertThat(statusSecond.generations().getFirst().generationId())
                .isNotEqualTo(statusFirst.generations().getFirst().generationId());
        assertThat(statusSecond.nextCursor()).isNull();

        assertBadRequest(() -> analyticsService.merchantSummary(ownerContext,
                intervalStart.plusSeconds(1), rangeEnd, "ALL"));
        assertBadRequest(() -> analyticsService.merchantTimeSeries(ownerContext,
                intervalStart, rangeEnd.minusSeconds(1), "HOUR", "ALL", 100));
        assertBadRequest(() -> analyticsService.merchantSummary(ownerContext,
                intervalStart, DatabaseInstant.now().plus(2, ChronoUnit.HOURS)
                        .truncatedTo(ChronoUnit.HOURS), "ALL"));
        assertBadRequest(() -> analyticsService.merchantSummary(ownerContext,
                intervalStart, DatabaseInstant.now().plusNanos(1), "ALL"));
        assertBadRequest(() -> analyticsService.merchantSummary(ownerContext,
                intervalStart, rangeEnd, "LIVE_ONLY", 1, summaryFirst.nextCursor()));
        assertBadRequest(() -> analyticsService.merchantTimeSeries(ownerContext,
                intervalStart, rangeEnd, "DAY", "ALL", 1, timeFirst.nextCursor()));
        assertBadRequest(() -> analyticsService.projectionStatus(
                context(UUID.randomUUID()), 1, statusFirst.nextCursor()));
        assertBadRequest(() -> analyticsService.merchantSummary(ownerContext,
                intervalStart, rangeEnd, "ALL", 0, null));
        assertBadRequest(() -> analyticsService.merchantTimeSeries(ownerContext,
                intervalStart, rangeEnd, "HOUR", "ALL", 101));
        assertBadRequest(() -> analyticsService.projectionStatus(ownerContext, 101, null));
    }

    @Test
    void fraudDecisionEventWithoutExactSourceLinkIsQuarantinedAndCannotAdvanceProjection() {
        MerchantFixture owner = merchant("analytics-missing-fraud-lineage");
        PaymentFixture payment = payment(owner, "USD", 3_200, TrafficOrigin.MERCHANT_API);
        DomainEvent created = append(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.createdAt(), createdPayload(payment));
        assertThat(consumerService.process(transported(created, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        ObjectNode decisionPayload = objectMapper.createObjectNode();
        decisionPayload.put("action", "ALLOW");
        decisionPayload.put("riskLevel", "LOW");
        decisionPayload.putArray("reasonCodes").add("NO_RULE_MATCH");
        decisionPayload.put("rulesetVersion", "rules_v1");
        decisionPayload.put("riskScore", 500);
        decisionPayload.put("featureCatalogueVersion", "features_v1");
        DomainEvent unlinked = append(payment, DomainEventType.FRAUD_DECISION_CREATED,
                payment.createdAt().plusSeconds(1), decisionPayload);

        assertThat(consumerService.process(transported(unlinked, nextOffset++)))
                .isEqualTo(ProjectionProcessingOutcome.QUARANTINED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_stream_version FROM payment_risk_projections
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, Long.class, owner.merchant().getId(), payment.intent().getId()))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_quarantine
                WHERE merchant_id = ? AND domain_event_id = ?
                  AND reason_code = 'PROJECTION_INVARIANT_FAILURE'
                """, Integer.class, owner.merchant().getId(), unlinked.getId())).isOne();
    }

    private MerchantFixture merchant(String name) {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                name, name + "-key-" + UUID.randomUUID()));
        return new MerchantFixture(merchant);
    }

    private PaymentFixture payment(MerchantFixture owner, String currency, long amount,
            TrafficOrigin origin) {
        return payment(owner, currency, amount, origin,
                DatabaseInstant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS));
    }

    private PaymentFixture payment(MerchantFixture owner, String currency, long amount,
            TrafficOrigin origin, Instant occurredAt) {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                owner.merchant().getId(), "email-" + UUID.randomUUID(), null));
        PaymentIntent intent = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                owner.merchant().getId(), customer.getId(), amount, currency,
                CaptureMethod.AUTOMATIC, null, java.util.Map.of(), origin));
        return new PaymentFixture(owner, intent, amount, currency, origin,
                occurredAt.truncatedTo(ChronoUnit.MICROS));
    }

    private DomainEvent append(PaymentFixture payment, DomainEventType eventType,
            Instant occurredAt, ObjectNode payload) {
        return append(payment.owner(), payment.intent().getId(), DomainStreamType.PAYMENT,
                eventType, occurredAt, payload, payment.origin());
    }

    private DomainEvent append(MerchantFixture owner, UUID streamId,
            DomainStreamType streamType, DomainEventType eventType, Instant occurredAt,
            ObjectNode payload, TrafficOrigin origin) {
        DomainEventCommand command = new DomainEventCommand(owner.merchant().getId(),
                streamType, streamId, eventType, DomainEventCatalogue.INITIAL_EVENT_VERSION,
                occurredAt.truncatedTo(ChronoUnit.MICROS), "projection-contract-" + UUID.randomUUID(),
                null, "projection-request-" + UUID.randomUUID(), null,
                new DomainEventActor(DomainEventActorType.SYSTEM,
                        "projection-consumer-contract"), origin, payload);
        DomainEventWriteResult result = transaction.execute(status -> eventWriter.append(command));
        if (result == null) {
            throw new IllegalStateException("Domain event append returned no result");
        }
        return eventRepository.findById(result.eventId()).orElseThrow();
    }

    private DomainEventCommand eventCommand(PaymentFixture payment,
            DomainEventType eventType, Instant occurredAt, ObjectNode payload) {
        return new DomainEventCommand(payment.owner().merchant().getId(),
                DomainStreamType.PAYMENT, payment.intent().getId(), eventType,
                DomainEventCatalogue.INITIAL_EVENT_VERSION,
                occurredAt.truncatedTo(ChronoUnit.MICROS),
                "projection-contract-" + UUID.randomUUID(), null,
                "projection-request-" + UUID.randomUUID(), null,
                new DomainEventActor(DomainEventActorType.SYSTEM,
                        "projection-consumer-contract"), payment.origin(), payload);
    }

    private TransportedDomainEvent transported(DomainEvent event, long offset) {
        return new TransportedDomainEvent(PaymentRiskProjectionConsumerService.TOPIC_NAME,
                0, offset, TenantQualifiedStreamKey.encode(event.getMerchantId(),
                        event.getStreamType(), event.getStreamId()),
                event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                event.getEventRecordSha256());
    }

    private ConsumerRecord<byte[], byte[]> kafkaRecord(DomainEvent event, long offset) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, offset,
                TenantQualifiedStreamKey.encode(event.getMerchantId(), event.getStreamType(),
                        event.getStreamId()), event.getEventRecordBytes());
        record.headers().add(DomainEventKafkaHeaders.EVENT_ID,
                event.getId().toString().getBytes(StandardCharsets.US_ASCII));
        record.headers().add(DomainEventKafkaHeaders.MERCHANT_ID,
                event.getMerchantId().toString().getBytes(StandardCharsets.US_ASCII));
        record.headers().add(DomainEventKafkaHeaders.EVENT_RECORD_SHA256,
                event.getEventRecordSha256().getBytes(StandardCharsets.US_ASCII));
        return record;
    }

    private ObjectNode createdPayload(PaymentFixture payment) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", payment.amount());
        payload.put("currency", payment.currency());
        payload.put("captureMethod", "AUTOMATIC");
        return payload;
    }

    private ObjectNode moneyPayload(long amount, String currency) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", amount);
        payload.put("currency", currency);
        return payload;
    }

    private ObjectNode labelPayload(String label, Instant availableAt,
            boolean superseded) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("label", label);
        payload.put("source", "MANUAL_REVIEW");
        payload.put("availableAt", availableAt.toString());
        if (superseded) {
            payload.put("supersedesLabelReference",
                    UUID.fromString("00000000-0000-0000-0000-000000000777").toString());
        }
        return payload;
    }

    private ObjectNode incidentPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("severity", "HIGH");
        payload.put("evidenceCount", 5);
        return payload;
    }

    private long cohortValue(UUID merchantId, Instant occurredAt, String column) {
        String allowedColumn = switch (column) {
            case "unlabeled_count" -> "unlabeled_count";
            case "confirmed_fraud_count" -> "confirmed_fraud_count";
            default -> throw new IllegalArgumentException("Unknown cohort column");
        };
        String query = "SELECT " + allowedColumn + " FROM merchant_risk_aggregates "
                + "WHERE merchant_id = ? AND interval_type = 'HOUR' "
                + "AND interval_start = ? AND traffic_origin_policy = 'ALL' "
                + "AND currency = 'USD'";
        Long value = jdbcTemplate.queryForObject(query, Long.class, merchantId,
                Timestamp.from(occurredAt.truncatedTo(ChronoUnit.HOURS)));
        return value == null ? 0 : value;
    }

    private int count(String table, UUID merchantId) {
        String allowedTable = switch (table) {
            case "consumer_inbox" -> "consumer_inbox";
            case "payment_risk_projections" -> "payment_risk_projections";
            default -> throw new IllegalArgumentException("Unknown count table");
        };
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + allowedTable + " WHERE merchant_id = ?",
                Integer.class, merchantId);
        return value == null ? 0 : value;
    }

    private RiskOperatorContext context(UUID merchantId) {
        return new RiskOperatorContext(merchantId, UUID.randomUUID(), "viewer",
                RiskOperatorRole.VIEWER, "risk_viewer");
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private record MerchantFixture(Merchant merchant) {
    }

    private record PaymentFixture(MerchantFixture owner, PaymentIntent intent,
            long amount, String currency, TrafficOrigin origin, Instant createdAt) {
    }
}
