package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

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
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.projection.ProjectionGenerationUnavailableException;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.projection.ProjectionProcessingOutcome;
import com.thaap.payments.projection.TransportedDomainEvent;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C50-CON-002}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}). The activate and
 * rollback paths were already proven end to end; what was missing was forced concurrent
 * ordering, which needs a seam inside the activation transaction. That seam is
 * {@link ProjectionRebuildExecutionProbe#beforeGenerationActivation}, which fires with the
 * definition row locked and the generation switch not yet written.
 *
 * <p>Real PostgreSQL is not optional here. Every claim is about lock ordering, snapshot
 * visibility and a serialized switch; an in-memory database would answer all of them yes
 * without any of them being true.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProjectionGenerationActivationPostgreSqlConcurrencyTests {

    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private ProjectionRebuildControlService controlService;
    @Autowired private ProjectionRebuildDispatcher dispatcher;
    @Autowired private PlatformOperatorService operatorService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService consumerService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @MockitoSpyBean private ProjectionRebuildExecutionProbe executionProbe;

    private String dataOperatorKey;
    private long liveOffset;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        liveOffset = 0;
        PlatformOperatorService.MutationResult administrator = operatorService.bootstrap(
                BOOTSTRAP_TOKEN, "activation-race-admin-bootstrap",
                "Activation race administrator", "activation-race-admin-request");
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administrator.credential(), "activation-race-data-operator",
                "Activation race data operator", PlatformOperatorRole.DATA_OPERATOR,
                "activation-race-data-request");
        dataOperatorKey = dataOperator.credential();
    }

    /**
     * Cancel must not win after activation has begun. Both operations open by locking the
     * projection definition, so the loser cannot slip between the reconciliation snapshot and
     * the switch; it waits, and then finds a rebuild it may no longer cancel.
     *
     * <p>The wait is asserted, not assumed. Without the timeout check this test would pass
     * just as happily if the two operations never overlapped at all.
     */
    @Test
    void aCancelArrivingDuringActivationWaitsForTheSwitchAndThenLoses() throws Exception {
        ReconciledRebuild rebuild = reconciledRebuild("activate-cancel-race", 2);
        UUID priorActive = activeGeneration();

        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        pauseActivation(activationPaused, releaseActivation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectionRebuildControlService.ActivationView> activation =
                    executor.submit(() -> controlService.activate(dataOperatorKey,
                            "activate-during-cancel", ProjectionNames.PAYMENT_RISK,
                            rebuild.rebuildId(), "activate-during-cancel-request"));
            assertThat(activationPaused.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> cancel = executor.submit(() -> {
                try {
                    controlService.cancel(dataOperatorKey, "cancel-during-activation",
                            ProjectionNames.PAYMENT_RISK, rebuild.rebuildId(),
                            "cancel-during-activation-request");
                    return null;
                } catch (Throwable thrown) {
                    return thrown;
                }
            });

            // The definition row lock is held by the paused activation, so the cancel cannot
            // resolve either way while the switch is in flight.
            assertThatThrownBy(() -> cancel.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(activeGeneration()).isEqualTo(priorActive);

            releaseActivation.countDown();
            assertThat(activation.get(15, TimeUnit.SECONDS).activated()).isTrue();
            assertThat(cancel.get(15, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseActivation.countDown();
            executor.shutdownNow();
        }

        assertThat(activeGeneration()).isEqualTo(rebuild.generationId());
        assertThat(generationStatus(rebuild.generationId())).isEqualTo("ACTIVE");
        assertThat(rebuildStatus(rebuild.rebuildId())).isEqualTo("ACTIVATED");
        assertThat(activeGenerationCount()).isEqualTo(1);
        assertThat(eventTypes(rebuild.rebuildId())).contains("ACTIVATED")
                .doesNotContain("CANCELED");
    }

    /**
     * Two operators activating the same reconciled rebuild must produce one activation, one
     * retirement of the prior generation, and one activation event.
     *
     * <p>Two operators activating <em>different</em> generations of one projection is not
     * reachable: {@code start} refuses a second rebuild while any rebuild for the definition
     * is unfinished, and RECONCILED counts as unfinished, so a definition never holds two
     * activatable generations at once. That exclusion is enforced at start and asserted here
     * rather than raced, because there is no interleaving that produces it.
     */
    @Test
    void twoConcurrentActivationsOfOneReconciledRebuildActivateItExactlyOnce() throws Exception {
        ReconciledRebuild rebuild = reconciledRebuild("activate-activate-race", 2);
        UUID priorActive = activeGeneration();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> activateQuietly(rebuild.rebuildId(),
                    "activate-race-first", ready, start));
            Future<Object> second = executor.submit(() -> activateQuietly(rebuild.rebuildId(),
                    "activate-race-second", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));
            long activated = outcomes.stream()
                    .filter(ProjectionRebuildControlService.ActivationView.class::isInstance)
                    .map(ProjectionRebuildControlService.ActivationView.class::cast)
                    .filter(ProjectionRebuildControlService.ActivationView::activated)
                    .count();
            assertThat(activated).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(activeGeneration()).isEqualTo(rebuild.generationId());
        assertThat(activeGenerationCount()).isEqualTo(1);
        assertThat(generationStatus(priorActive)).isEqualTo("RETIRED");
        assertThat(eventTypes(rebuild.rebuildId()).stream().filter("ACTIVATED"::equals).count())
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_rebuilds
                WHERE id = ? AND lifecycle_status = 'ACTIVATED'
                  AND previous_active_generation_id = ?
                """, Integer.class, rebuild.rebuildId(), priorActive)).isEqualTo(1);
    }

    /**
     * Rollback must not race a live activation. Both restore a definition pointer, and both
     * open by locking the definition, so a rollback arriving mid-activation waits and then
     * finds the generation it was aiming at is no longer a retained rollback target.
     */
    @Test
    void aRollbackArrivingDuringActivationWaitsAndCannotUndoTheSwitch() throws Exception {
        ReconciledRebuild rebuild = reconciledRebuild("activate-rollback-race", 2);
        UUID priorActive = activeGeneration();

        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        pauseActivation(activationPaused, releaseActivation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectionRebuildControlService.ActivationView> activation =
                    executor.submit(() -> controlService.activate(dataOperatorKey,
                            "activate-during-rollback", ProjectionNames.PAYMENT_RISK,
                            rebuild.rebuildId(), "activate-during-rollback-request"));
            assertThat(activationPaused.await(10, TimeUnit.SECONDS)).isTrue();

            // Rolling back onto the generation that is currently active, while the switch
            // that retires it is in flight.
            Future<Throwable> rollback = executor.submit(() -> {
                try {
                    controlService.rollback(dataOperatorKey, "rollback-during-activation",
                            ProjectionNames.PAYMENT_RISK, priorActive,
                            "rollback-during-activation-request");
                    return null;
                } catch (Throwable thrown) {
                    return thrown;
                }
            });
            assertThatThrownBy(() -> rollback.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseActivation.countDown();
            assertThat(activation.get(15, TimeUnit.SECONDS).activated()).isTrue();
            assertThat(rollback.get(15, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseActivation.countDown();
            executor.shutdownNow();
        }

        assertThat(activeGeneration()).isEqualTo(rebuild.generationId());
        assertThat(activeGenerationCount()).isEqualTo(1);
        assertThat(generationStatus(priorActive)).isEqualTo("RETIRED");
    }

    /**
     * A reader must never see zero active generations or two. The switch retires the old
     * generation, activates the new one and moves the pointer in one transaction, so an
     * uncommitted switch is invisible and a committed one is complete.
     */
    @Test
    void aReaderNeverObservesZeroOrTwoActiveGenerationsDuringActivation() throws Exception {
        ReconciledRebuild rebuild = reconciledRebuild("activation-reader", 2);
        UUID priorActive = activeGeneration();

        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        pauseActivation(activationPaused, releaseActivation);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProjectionRebuildControlService.ActivationView> activation =
                    executor.submit(() -> controlService.activate(dataOperatorKey,
                            "activate-with-readers", ProjectionNames.PAYMENT_RISK,
                            rebuild.rebuildId(), "activate-with-readers-request"));
            assertThat(activationPaused.await(10, TimeUnit.SECONDS)).isTrue();

            // Mid-switch, from outside the activation transaction, the world is unchanged.
            for (int probe = 0; probe < 20; probe++) {
                assertThat(activeGenerationCount()).isEqualTo(1);
                assertThat(activeGeneration()).isEqualTo(priorActive);
                assertThat(generationStatus(rebuild.generationId())).isEqualTo("RECONCILED");
            }

            releaseActivation.countDown();
            assertThat(activation.get(15, TimeUnit.SECONDS).activated()).isTrue();
        } finally {
            releaseActivation.countDown();
            executor.shutdownNow();
        }

        assertThat(activeGenerationCount()).isEqualTo(1);
        assertThat(activeGeneration()).isEqualTo(rebuild.generationId());
        assertThat(generationStatus(priorActive)).isEqualTo("RETIRED");
    }

    /**
     * A live write that commits <em>before</em> the activation snapshot leaves the shadow
     * behind the source. Activation must notice the drift from the recorded source digest,
     * catch the shadow up and revalidate, rather than exposing a generation that is missing
     * an event the retired one already had.
     */
    @Test
    void aLiveWriteCommittedBeforeTheActivationSnapshotIsCaughtUpBeforeTheSwitch() {
        ReconciledRebuild rebuild = reconciledRebuild("live-write-before-snapshot", 2);
        UUID priorActive = activeGeneration();

        UUID lateIntentId = commitLivePayment(rebuild.merchantId(), rebuild.customerId(),
                7_100L);
        assertThat(projectedPaymentCount(priorActive)).isEqualTo(3);
        assertThat(projectedPaymentCount(rebuild.generationId())).isEqualTo(2);

        ProjectionRebuildControlService.ActivationView activation = controlService.activate(
                dataOperatorKey, "activate-after-late-live-write",
                ProjectionNames.PAYMENT_RISK, rebuild.rebuildId(),
                "activate-after-late-live-write-request");

        assertThat(activation.activated()).isTrue();
        assertThat(activation.mismatchFamilies()).isEmpty();
        assertThat(activeGeneration()).isEqualTo(rebuild.generationId());
        assertThat(activeGenerationCount()).isEqualTo(1);
        // The event that arrived after reconciliation is present in the generation readers
        // were switched onto, not only in the one that was retired.
        assertThat(projectedPaymentCount(rebuild.generationId())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ? AND payment_intent_id = ?
                """, Integer.class, rebuild.generationId(), lateIntentId)).isEqualTo(1);
    }

    /**
     * A live write racing the switch itself must not split across generations.
     *
     * <p>The consumer takes the definition pointer and its generation {@code FOR UPDATE}
     * before applying, so a write arriving mid-switch waits on the activation. What it finds
     * when the lock is released is not the new generation but nothing: under read-committed
     * re-evaluation the generation row it locked no longer satisfies
     * {@code generation.id = definition.active_generation_id}, and the consumer raises
     * {@link com.thaap.payments.projection.ProjectionGenerationUnavailableException}.
     *
     * <p>That refusal is the invariant holding, not failing. The exception is deliberately
     * not caught by {@code process} and so is not quarantined — quarantining a valid event
     * because a switch happened underneath it would turn a transient race into permanent
     * operator work. The event is refused, stays unapplied in both generations, and lands in
     * the newly active one when redelivered. The one outcome ruled out is the dangerous one:
     * the write never lands in the generation being retired.
     */
    @Test
    void aLiveWriteRacingTheSwitchIsRefusedAndLandsInTheNewGenerationOnRetry() throws Exception {
        ReconciledRebuild rebuild = reconciledRebuild("live-write-after-snapshot", 2);
        UUID priorActive = activeGeneration();

        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        pauseActivation(activationPaused, releaseActivation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        LiveEvent late;
        try {
            Future<ProjectionRebuildControlService.ActivationView> activation =
                    executor.submit(() -> controlService.activate(dataOperatorKey,
                            "activate-racing-live-write", ProjectionNames.PAYMENT_RISK,
                            rebuild.rebuildId(), "activate-racing-live-write-request"));
            assertThat(activationPaused.await(10, TimeUnit.SECONDS)).isTrue();

            // The event is appended only after activation has taken its source snapshot, so
            // this is genuinely the after-snapshot ordering rather than source drift the
            // activation could have detected up front. Drift arriving before the snapshot is
            // a different case and has its own test.
            late = appendLivePayment(rebuild.merchantId(), rebuild.customerId(), 8_200L);
            Future<ProjectionProcessingOutcome> liveWrite =
                    executor.submit(() -> applyLive(late));
            assertThatThrownBy(() -> liveWrite.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseActivation.countDown();
            assertThat(activation.get(15, TimeUnit.SECONDS).activated()).isTrue();
            assertThatThrownBy(() -> liveWrite.get(15, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ProjectionGenerationUnavailableException.class);
        } finally {
            releaseActivation.countDown();
            executor.shutdownNow();
        }

        assertThat(activeGeneration()).isEqualTo(rebuild.generationId());
        assertThat(activeGenerationCount()).isEqualTo(1);

        // Refused, not misfiled: absent from both generations, and not quarantined.
        assertThat(projectedPayment(priorActive, late.paymentIntentId())).isZero();
        assertThat(projectedPayment(rebuild.generationId(), late.paymentIntentId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_quarantine", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_transport_quarantine", Integer.class)).isZero();

        // Redelivery is the recovery path, and it lands in the generation that won.
        assertThat(applyLive(late)).isEqualTo(ProjectionProcessingOutcome.APPLIED);
        assertThat(projectedPayment(rebuild.generationId(), late.paymentIntentId()))
                .isEqualTo(1);
        assertThat(projectedPayment(priorActive, late.paymentIntentId())).isZero();
    }

    /**
     * Rollback eligibility is part of this row's invariant: only a retained, reconciled,
     * handler-compatible generation may be selected. A generation belonging to another
     * projection definition, and one whose handler version no longer matches, must both be
     * refused — neither is reachable through an ordering race, so both are asserted directly.
     */
    @Test
    void rollbackRefusesAForeignDefinitionAndAnIncompatibleHandlerVersion() {
        ReconciledRebuild payment = reconciledRebuild("rollback-eligibility", 2);
        UUID priorActive = activeGeneration();
        assertThat(controlService.activate(dataOperatorKey, "activate-rollback-eligibility",
                ProjectionNames.PAYMENT_RISK, payment.rebuildId(),
                "activate-rollback-eligibility-request").activated()).isTrue();

        // A retained generation of the other projection definition, same shape, wrong owner.
        UUID foreignGeneration = jdbcTemplate.queryForObject("""
                SELECT generation.id FROM projection_generations generation
                JOIN projection_definitions definition
                  ON definition.id = generation.projection_definition_id
                WHERE definition.projection_name = ?
                ORDER BY generation.generation_number LIMIT 1
                """, UUID.class, ProjectionNames.MERCHANT_RISK);
        assertThatThrownBy(() -> controlService.rollback(dataOperatorKey,
                "rollback-foreign-definition", ProjectionNames.PAYMENT_RISK,
                foreignGeneration, "rollback-foreign-definition-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        refusal -> assertThat(refusal.getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(activeGeneration()).isEqualTo(payment.generationId());

        // An incompatible-handler rollback target cannot be constructed, which is a stronger
        // result than a test that one is refused. A generation's handler version is immutable
        // by trigger, and a generation is created carrying its definition's version, so a
        // retained target can never drift from the definition it belongs to.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE projection_generations SET handler_version = 'v0' WHERE id = ?
                """, priorActive)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("projection generation mutation is invalid");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT handler_version FROM projection_generations WHERE id = ?
                """, String.class, priorActive)).isEqualTo(handlerVersion());
        assertThat(activeGeneration()).isEqualTo(payment.generationId());
        assertThat(activeGenerationCount()).isEqualTo(1);
    }

    /** Holds the activation transaction open at the switch boundary. */
    private void pauseActivation(CountDownLatch paused, CountDownLatch release) {
        AtomicInteger pauses = new AtomicInteger();
        doAnswer(invocation -> {
            if (pauses.getAndIncrement() == 0) {
                paused.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("activation was never released");
                }
            }
            return null;
        }).when(executionProbe).beforeGenerationActivation(any());
    }

    private Object activateQuietly(UUID rebuildId, String idempotencyKey,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("activation race never started");
            }
            return controlService.activate(dataOperatorKey, idempotencyKey,
                    ProjectionNames.PAYMENT_RISK, rebuildId, idempotencyKey + "-request");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (RuntimeException refused) {
            return refused;
        }
    }

    /**
     * Seeds {@code paymentCount} payment streams into the live projection, then rebuilds and
     * reconciles a shadow generation over them, leaving it RECONCILED and activatable.
     */
    private ReconciledRebuild reconciledRebuild(String label, int paymentCount) {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant(label + "-merchant", label + "-api-key"));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), label + "-customer-hash", null));
        for (int index = 0; index < paymentCount; index++) {
            commitLivePayment(merchant.getId(), customer.getId(), 3_000L + index);
        }

        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-" + label, ProjectionNames.PAYMENT_RISK, "ALL",
                "start-" + label + "-request");
        dispatcher.launchSynchronously(started.rebuildId());
        ProjectionRebuildControlService.ReconciliationView reconciliation =
                controlService.reconcile(dataOperatorKey, "reconcile-" + label,
                        ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                        "reconcile-" + label + "-request");
        assertThat(reconciliation.passed()).isTrue();
        assertThat(rebuildStatus(started.rebuildId())).isEqualTo("RECONCILED");
        return new ReconciledRebuild(started.rebuildId(), started.generationId(),
                merchant.getId(), customer.getId());
    }

    /** Appends a payment-created event and applies it to the active generation. */
    private UUID commitLivePayment(UUID merchantId, UUID customerId, long amount) {
        LiveEvent event = appendLivePayment(merchantId, customerId, amount);
        assertThat(applyLive(event)).isEqualTo(ProjectionProcessingOutcome.APPLIED);
        return event.paymentIntentId();
    }

    private LiveEvent appendLivePayment(UUID merchantId, UUID customerId, long amount) {
        PaymentIntent intent = paymentIntentRepository.saveAndFlush(
                new PaymentIntent(merchantId, customerId, amount, "USD"));
        TransactionTemplate sourceTransaction = new TransactionTemplate(transactionManager);
        DomainEventWriteResult result = sourceTransaction.execute(status ->
                eventWriter.append(createdCommand(merchantId, intent)));
        assertThat(result).isNotNull();
        return new LiveEvent(intent.getId(), result.eventId());
    }

    private ProjectionProcessingOutcome applyLive(LiveEvent live) {
        DomainEvent event = eventRepository.findById(live.eventId()).orElseThrow();
        return consumerService.process(new TransportedDomainEvent(
                PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, liveOffset++,
                TenantQualifiedStreamKey.encode(event.getMerchantId(), event.getStreamType(),
                        event.getStreamId()),
                event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                event.getEventRecordSha256()));
    }

    private DomainEventCommand createdCommand(UUID merchantId, PaymentIntent intent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", intent.getAmount());
        payload.put("currency", intent.getCurrency());
        payload.put("captureMethod", intent.getCaptureMethod().name());
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, intent.getId(),
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, Instant.now().minusSeconds(1),
                "activation-race-correlation", null, "activation-race-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "activation-race-producer"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private UUID activeGeneration() {
        return jdbcTemplate.queryForObject("""
                SELECT active_generation_id FROM projection_definitions
                WHERE projection_name = ?
                """, UUID.class, ProjectionNames.PAYMENT_RISK);
    }

    private int activeGenerationCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_generations generation
                JOIN projection_definitions definition
                  ON definition.id = generation.projection_definition_id
                WHERE definition.projection_name = ?
                  AND generation.lifecycle_status = 'ACTIVE'
                """, Integer.class, ProjectionNames.PAYMENT_RISK);
    }

    private String generationStatus(UUID generationId) {
        return jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM projection_generations WHERE id = ?
                """, String.class, generationId);
    }

    private String handlerVersion() {
        return jdbcTemplate.queryForObject("""
                SELECT handler_version FROM projection_definitions
                WHERE projection_name = ?
                """, String.class, ProjectionNames.PAYMENT_RISK);
    }

    private String rebuildStatus(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
    }

    private int projectedPayment(UUID generationId, UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ? AND payment_intent_id = ?
                """, Integer.class, generationId, paymentIntentId);
    }

    private int projectedPaymentCount(UUID generationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ?
                """, Integer.class, generationId);
    }

    private List<String> eventTypes(UUID rebuildId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? ORDER BY event_sequence
                """, String.class, rebuildId);
    }

    private record ReconciledRebuild(UUID rebuildId, UUID generationId, UUID merchantId,
            UUID customerId) {
    }

    private record LiveEvent(UUID paymentIntentId, UUID eventId) {
    }
}
