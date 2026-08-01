package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FeatureFreshnessStatus;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FeatureSnapshotRepository;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.fraud.FraudRiskLevel;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentDomainEventService;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.projection.ProjectionProcessingOutcome;
import com.thaap.payments.projection.TransportedDomainEvent;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest
class ProjectionRebuildControlTests {

    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";

    @Autowired private ProjectionRebuildControlService controlService;
    @Autowired private ProjectionRebuildDispatcher dispatcher;
    @Autowired private PlatformOperatorService operatorService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private FraudDecisionRepository fraudDecisionRepository;
    @Autowired private FeatureSnapshotRepository featureSnapshotRepository;
    @Autowired private PaymentDomainEventService paymentDomainEventService;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService consumerService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;

    private String administratorKey;
    private String dataOperatorKey;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        PlatformOperatorService.MutationResult administrator = operatorService.bootstrap(
                BOOTSTRAP_TOKEN, "rebuild-admin-bootstrap", "Rebuild administrator",
                "rebuild-admin-request");
        administratorKey = administrator.credential();
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administratorKey, "rebuild-data-operator", "Rebuild data operator",
                PlatformOperatorRole.DATA_OPERATOR, "rebuild-data-request");
        dataOperatorKey = dataOperator.credential();
    }

    @Test
    void emptyShadowBuildReconcilesActivatesAndRollsBackWithoutSourceMutation() {
        SourceCounts before = sourceCounts();
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-payment-shadow", ProjectionNames.PAYMENT_RISK,
                "ALL", "start-payment-shadow-request");
        ProjectionRebuildControlService.RebuildView replay = controlService.start(
                dataOperatorKey, "start-payment-shadow", ProjectionNames.PAYMENT_RISK,
                "ALL", "start-payment-shadow-replay");

        assertThat(started.lifecycleStatus()).isEqualTo("QUEUED");
        assertThat(started.expectedInputCount()).isZero();
        assertThat(replay.rebuildId()).isEqualTo(started.rebuildId());
        assertThat(replay.replayed()).isTrue();

        dispatcher.launchSynchronously(started.rebuildId());
        assertThat(controlService.status(dataOperatorKey, ProjectionNames.PAYMENT_RISK,
                started.rebuildId()).lifecycleStatus()).isEqualTo("CATCHING_UP");

        ProjectionRebuildControlService.ReconciliationView reconciliation =
                controlService.reconcile(dataOperatorKey, "reconcile-payment-shadow",
                        ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                        "reconcile-payment-shadow-request");
        assertThat(reconciliation.passed()).isTrue();
        assertThat(reconciliation.mismatchFamilies()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_reconciliations
                WHERE projection_rebuild_id = ? AND result_status = 'PASS'
                """, Integer.class, started.rebuildId())).isEqualTo(5);

        ProjectionRebuildControlService.ActivationView activation = controlService.activate(
                dataOperatorKey, "activate-payment-shadow", ProjectionNames.PAYMENT_RISK,
                started.rebuildId(), "activate-payment-shadow-request");
        assertThat(activation.activated()).isTrue();
        assertThat(activeGeneration(ProjectionNames.PAYMENT_RISK))
                .isEqualTo(started.generationId());

        UUID baselineGeneration = UUID.fromString(
                "00000000-0000-0000-0000-000000000911");
        ProjectionRebuildControlService.RollbackView rollback = controlService.rollback(
                dataOperatorKey, "rollback-payment-shadow", ProjectionNames.PAYMENT_RISK,
                baselineGeneration, "rollback-payment-shadow-request");
        assertThat(rollback.activated()).isTrue();
        assertThat(activeGeneration(ProjectionNames.PAYMENT_RISK))
                .isEqualTo(baselineGeneration);
        assertThat(sourceCounts()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForList("""
                SELECT event_type FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? ORDER BY event_sequence
                """, String.class, started.rebuildId())).containsExactly(
                        "QUEUED", "LEASED", "CATCH_UP_STARTED", "RECONCILED",
                        "ACTIVATED", "ROLLED_BACK");
    }

    @Test
    void cancellationIsFencedAndAdministratorCannotOperateDataPlane() {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-merchant-shadow", ProjectionNames.MERCHANT_RISK,
                "ALL", "start-merchant-shadow-request");
        ProjectionRebuildControlService.RebuildView canceled = controlService.cancel(
                dataOperatorKey, "cancel-merchant-shadow", ProjectionNames.MERCHANT_RISK,
                started.rebuildId(), "cancel-merchant-shadow-request");
        assertThat(canceled.lifecycleStatus()).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM projection_generations WHERE id = ?
                """, String.class, started.generationId())).isEqualTo("CANCELED");

        assertThatThrownBy(() -> controlService.start(administratorKey,
                "admin-cannot-rebuild", ProjectionNames.PAYMENT_RISK, "ALL",
                "admin-cannot-rebuild-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void changedIdempotentReplayIsRejectedWithoutASecondGeneration() {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "stable-start-key", ProjectionNames.PAYMENT_RISK,
                "ALL", "stable-start-request");
        assertThatThrownBy(() -> controlService.start(dataOperatorKey,
                "stable-start-key", ProjectionNames.MERCHANT_RISK,
                "ALL", "conflicting-start-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_generations
                WHERE projection_definition_id =
                    '00000000-0000-0000-0000-000000000901'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void oneHundredAndOneInputsCommitAsOneFullAndOnePartialChunk() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("rebuild-chunk-merchant", "rebuild-chunk-api-key"));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), "rebuild-customer-hash", null));
        TransactionTemplate sourceTransaction = new TransactionTemplate(transactionManager);
        List<DomainEventWriteResult> sourceEvents = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            PaymentIntent intent = paymentIntentRepository.saveAndFlush(
                    new PaymentIntent(merchant.getId(), customer.getId(), 1000L + index, "USD"));
            DomainEventWriteResult result = sourceTransaction.execute(status ->
                    eventWriter.append(createdCommand(merchant.getId(), intent)));
            assertThat(result).isNotNull();
            sourceEvents.add(result);
        }
        long offset = 0;
        for (DomainEventWriteResult result : sourceEvents) {
            DomainEvent event = eventRepository.findById(result.eventId()).orElseThrow();
            ProjectionProcessingOutcome outcome = consumerService.process(
                    new TransportedDomainEvent(
                            PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, offset++,
                            TenantQualifiedStreamKey.encode(event.getMerchantId(),
                                    event.getStreamType(), event.getStreamId()),
                            event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                            event.getEventRecordSha256()));
            assertThat(outcome).isEqualTo(ProjectionProcessingOutcome.APPLIED);
        }
        SourceCounts before = sourceCounts();
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-101-payment-inputs", ProjectionNames.PAYMENT_RISK,
                "ALL", "start-101-payment-inputs-request");
        assertThat(started.expectedInputCount()).isEqualTo(101);

        dispatcher.launchSynchronously(started.rebuildId());

        assertThat(jdbcTemplate.queryForList("""
                SELECT processed_input_count FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? AND event_type = 'CHUNK_COMMITTED'
                ORDER BY event_sequence
                """, Long.class, started.rebuildId())).containsExactly(100L, 101L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ?
                """, Integer.class, started.generationId())).isEqualTo(101);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_stream_positions
                WHERE projection_generation_id = ?
                """, Integer.class, started.generationId())).isEqualTo(101);

        ProjectionRebuildControlService.ReconciliationView reconciliation =
                controlService.reconcile(dataOperatorKey, "reconcile-101-payment-inputs",
                        ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                        "reconcile-101-payment-inputs-request");
        assertThat(reconciliation.passed()).isTrue();
        assertThat(sourceCounts()).isEqualTo(before);
    }

    @Test
    void basisPointRiskScoreRebuildsWithoutWeakeningProjectionBounds() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("rebuild-risk-score-merchant", "rebuild-risk-score-api-key"));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), "rebuild-risk-score-customer", null));
        PaymentMethod paymentMethod = paymentMethodRepository.saveAndFlush(new PaymentMethod(
                merchant.getId(), customer.getId(), "rebuild-risk-score-token",
                "rebuild-risk-score-fingerprint", "424242", "4242",
                CardFunding.CREDIT, "US"));
        PaymentIntent intent = paymentIntentRepository.saveAndFlush(
                new PaymentIntent(merchant.getId(), customer.getId(), 5_000L, "USD"));
        PaymentAttempt attempt = paymentAttemptRepository.saveAndFlush(new PaymentAttempt(
                merchant.getId(), intent.getId(), paymentMethod.getId(),
                "rebuild-risk-score-attempt", 1));
        Instant decisionCompletedAt = Instant.now();
        ConfirmDecision sourceDecision = new ConfirmDecision(FraudDecisionAction.ALLOW,
                new BigDecimal("0.0500"), FraudRiskLevel.LOW, "rules_v1", null,
                List.of(FraudDecisionReasonCode.NO_RULE_MATCH));
        FraudDecision fraudDecision = fraudDecisionRepository.saveAndFlush(new FraudDecision(
                merchant.getId(), attempt.getId(), sourceDecision, 1,
                decisionCompletedAt.minusMillis(1), decisionCompletedAt,
                FeatureFreshnessStatus.FRESH, 0));
        FeatureSnapshot snapshot = featureSnapshotRepository.saveAndFlush(new FeatureSnapshot(
                merchant.getId(), fraudDecision.getId(), attempt.getId(), Map.of(),
                decisionCompletedAt, decisionCompletedAt, 0,
                FeatureFreshnessStatus.FRESH, List.of(), List.of()));
        attempt.attachFraudDecision(fraudDecision.getId());
        paymentAttemptRepository.saveAndFlush(attempt);
        TransactionTemplate sourceTransaction = new TransactionTemplate(transactionManager);
        DomainEventWriteResult created = sourceTransaction.execute(status ->
                eventWriter.append(createdCommand(merchant.getId(), intent)));
        assertThat(created).isNotNull();
        DomainEventWriteResult decision = sourceTransaction.execute(status ->
                paymentDomainEventService.fraudDecisionCreated(intent, fraudDecision, snapshot,
                        new DomainEventActor(DomainEventActorType.SYSTEM,
                                "rebuild-test-producer"),
                        "rebuild-risk-score-request", null));
        assertThat(decision).isNotNull();

        long offset = 0;
        for (DomainEventWriteResult result : List.of(created, decision)) {
            DomainEvent event = eventRepository.findById(result.eventId()).orElseThrow();
            assertThat(consumerService.process(new TransportedDomainEvent(
                    PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, offset++,
                    TenantQualifiedStreamKey.encode(event.getMerchantId(),
                            event.getStreamType(), event.getStreamId()),
                    event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                    event.getEventRecordSha256())))
                    .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        }

        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-basis-point-risk-score",
                ProjectionNames.PAYMENT_RISK, "ALL",
                "start-basis-point-risk-score-request");
        assertThat(started.expectedInputCount()).isEqualTo(2);
        dispatcher.launchSynchronously(started.rebuildId());

        assertThat(controlService.status(dataOperatorKey, ProjectionNames.PAYMENT_RISK,
                started.rebuildId()).lifecycleStatus()).isEqualTo("CATCHING_UP");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT risk_score FROM payment_risk_projections
                WHERE projection_generation_id = ? AND payment_intent_id = ?
                """, Integer.class, started.generationId(), intent.getId())).isEqualTo(500);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payment_risk_projections SET risk_score = 10001
                WHERE projection_generation_id = ? AND payment_intent_id = ?
                """, started.generationId(), intent.getId()))
                .isInstanceOf(DataAccessException.class);
        ProjectionRebuildControlService.ReconciliationView reconciliation =
                controlService.reconcile(dataOperatorKey,
                        "reconcile-basis-point-risk-score",
                        ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                        "reconcile-basis-point-risk-score-request");
        assertThat(reconciliation.passed()).isTrue();
    }

    @Test
    void catchUpCommitsAtMostOneHundredInputsPerOperatorRequest() {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-bounded-catchup", ProjectionNames.PAYMENT_RISK,
                "ALL", "start-bounded-catchup-request");
        dispatcher.launchSynchronously(started.rebuildId());

        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("catchup-merchant", "catchup-api-key"));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), "catchup-customer-hash", null));
        TransactionTemplate sourceTransaction = new TransactionTemplate(transactionManager);
        for (int index = 0; index < 101; index++) {
            PaymentIntent intent = paymentIntentRepository.saveAndFlush(
                    new PaymentIntent(merchant.getId(), customer.getId(), 2000L + index, "USD"));
            DomainEventWriteResult result = sourceTransaction.execute(status ->
                    eventWriter.append(createdCommand(merchant.getId(), intent)));
            assertThat(result).isNotNull();
            DomainEvent event = eventRepository.findById(result.eventId()).orElseThrow();
            assertThat(consumerService.process(new TransportedDomainEvent(
                    PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, index,
                    TenantQualifiedStreamKey.encode(event.getMerchantId(),
                            event.getStreamType(), event.getStreamId()),
                    event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                    event.getEventRecordSha256())))
                    .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        }

        ProjectionRebuildControlService.ReconciliationView first = controlService.reconcile(
                dataOperatorKey, "bounded-catchup-cycle-1", ProjectionNames.PAYMENT_RISK,
                started.rebuildId(), "bounded-catchup-cycle-1-request");
        assertThat(first.passed()).isFalse();
        assertThat(first.catchUpComplete()).isFalse();
        assertThat(first.catchUpAppliedInputCount()).isEqualTo(100);
        assertThat(first.catchUpRemainingInputCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_reconciliations
                WHERE projection_rebuild_id = ?
                """, Integer.class, started.rebuildId())).isZero();

        ProjectionRebuildControlService.ReconciliationView second = controlService.reconcile(
                dataOperatorKey, "bounded-catchup-cycle-2", ProjectionNames.PAYMENT_RISK,
                started.rebuildId(), "bounded-catchup-cycle-2-request");
        assertThat(second.passed()).isTrue();
        assertThat(second.catchUpComplete()).isTrue();
        assertThat(second.catchUpAppliedInputCount()).isEqualTo(1);
        assertThat(second.catchUpRemainingInputCount()).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT applied_input_count FROM projection_catchup_cycles
                WHERE projection_rebuild_id = ? ORDER BY cycle_number
                """, Integer.class, started.rebuildId())).containsExactly(100, 1);
    }

    @Test
    void immutableBootstrapFactReconcilesLegacyPaymentWithoutFabricatedEventIdentity() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("legacy-bootstrap-merchant", "legacy-bootstrap-api-key"));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), "legacy-bootstrap-customer", null));
        PaymentIntent legacyIntent = paymentIntentRepository.saveAndFlush(
                new PaymentIntent(merchant.getId(), customer.getId(), 7_500L, "USD"));

        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-legacy-bootstrap", ProjectionNames.PAYMENT_RISK,
                "ALL", "start-legacy-bootstrap-request");
        assertThat(started.expectedInputCount()).isEqualTo(1);
        dispatcher.launchSynchronously(started.rebuildId());

        ProjectionRebuildControlService.ReconciliationView reconciliation =
                controlService.reconcile(dataOperatorKey, "reconcile-legacy-bootstrap",
                        ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                        "reconcile-legacy-bootstrap-request");
        assertThat(reconciliation.passed()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ? AND payment_intent_id = ?
                  AND source_bootstrap_fact_id IS NOT NULL
                  AND last_stream_version = 0 AND last_domain_event_id IS NULL
                  AND last_event_record_sha256 IS NULL AND coverage_status = 'PARTIAL'
                """, Integer.class, started.generationId(), legacyIntent.getId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events WHERE stream_id = ?
                """, Integer.class, legacyIntent.getId())).isZero();

        ProjectionRebuildControlService.RebuildView merchantStarted = controlService.start(
                dataOperatorKey, "start-legacy-merchant-bootstrap",
                ProjectionNames.MERCHANT_RISK, "ALL",
                "start-legacy-merchant-bootstrap-request");
        dispatcher.launchSynchronously(merchantStarted.rebuildId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(source_bootstrap_fact_count), 0)
                FROM merchant_risk_aggregates
                WHERE projection_generation_id = ?
                """, Long.class, merchantStarted.generationId())).isEqualTo(4L);

        assertThat(jdbcTemplate.update("""
                UPDATE merchant_risk_aggregates
                SET intent_amount_minor = intent_amount_minor + 1
                WHERE projection_generation_id = ?
                """, merchantStarted.generationId())).isEqualTo(4);
        ProjectionRebuildControlService.ReconciliationView corruptedReconciliation =
                controlService.reconcile(dataOperatorKey,
                        "reject-corrupted-legacy-merchant-bootstrap",
                        ProjectionNames.MERCHANT_RISK, merchantStarted.rebuildId(),
                        "reject-corrupted-legacy-merchant-bootstrap-request");
        assertThat(corruptedReconciliation.passed()).isFalse();
        assertThat(corruptedReconciliation.mismatchFamilies())
                .contains(ProjectionReconciliationFamily.BUSINESS_INVARIANT);

        assertThat(jdbcTemplate.update("""
                UPDATE merchant_risk_aggregates
                SET intent_amount_minor = intent_amount_minor - 1
                WHERE projection_generation_id = ?
                """, merchantStarted.generationId())).isEqualTo(4);
        ProjectionRebuildControlService.ReconciliationView merchantReconciliation =
                controlService.reconcile(dataOperatorKey,
                        "reconcile-restored-legacy-merchant-bootstrap",
                        ProjectionNames.MERCHANT_RISK, merchantStarted.rebuildId(),
                        "reconcile-restored-legacy-merchant-bootstrap-request");
        assertThat(merchantReconciliation.passed()).isTrue();
    }

    private UUID activeGeneration(String projectionName) {
        return jdbcTemplate.queryForObject("""
                SELECT active_generation_id FROM projection_definitions
                WHERE projection_name = ?
                """, UUID.class, projectionName);
    }

    private SourceCounts sourceCounts() {
        return new SourceCounts(count("payment_intents"), count("payment_attempts"),
                count("authorizations"), count("refunds"), count("chargebacks"),
                count("fraud_decisions"), count("fraud_labels"),
                count("ledger_entries"), count("webhook_events"));
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private DomainEventCommand createdCommand(UUID merchantId, PaymentIntent intent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", intent.getAmount());
        payload.put("currency", intent.getCurrency());
        payload.put("captureMethod", intent.getCaptureMethod().name());
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, intent.getId(),
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, Instant.now().minusSeconds(1),
                "rebuild-chunk-contract", null, "rebuild-chunk-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "rebuild-test-producer"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private record SourceCounts(long paymentIntents, long paymentAttempts,
            long authorizations, long refunds, long chargebacks, long fraudDecisions,
            long fraudLabels, long ledgerEntries, long webhookEvents) {
    }
}
