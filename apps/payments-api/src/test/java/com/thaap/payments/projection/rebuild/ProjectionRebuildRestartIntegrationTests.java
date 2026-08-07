package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C50-OPS-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p><strong>What this suite does not do.</strong> It does not stop and start a JVM. The row's
 * text says "stop/restart the application between chunk and activation boundaries", and that is
 * not what is proven here, because no process-restart harness exists anywhere in this repository
 * and building one is a larger decision than this row. What is proven instead is the state a
 * killed process actually leaves behind and the mechanism that recovers from it: a rebuild
 * stranded {@code RUNNING} with nobody holding its lease, found by the durable queue scan, and
 * resumed from its last committed checkpoint without duplicate effect. That is a narrower claim
 * than the frozen text, and the row's status reflects the difference rather than papering over it.
 *
 * <p>The substitution is faithful in one specific respect worth stating. A mid-run failure inside
 * a live process is <em>not</em> resumable at all: {@link ProjectionRebuildJobListener} marks the
 * rebuild {@code FAILED} and a {@code FAILED} rebuild is not claimable. The only state that is
 * resumable is a {@code RUNNING} row whose owner never came back — which is precisely what JVM
 * death produces, and precisely what {@link ProjectionRebuildStore#recoverable} and
 * {@code last_checkpoint_key} exist to serve. So the interesting half of a restart is reachable
 * without restarting; the uninteresting half, Spring's own context lifecycle, is not covered.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProjectionRebuildRestartIntegrationTests {

    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";
    private static final int INPUT_COUNT = 101;

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
    @Autowired private ProjectionRebuildStore store;
    @Autowired private ProjectionRebuildMetrics metrics;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier("projectionShadowBuildJob") private Job shadowBuildJob;
    @Autowired @Qualifier("projectionRebuildExecutor") private Executor rebuildExecutor;
    @Autowired private PlatformOperatorService operatorService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private DatabaseCleaner databaseCleaner;
    @MockitoSpyBean private ProjectionRebuildExecutionProbe executionProbe;

    private String dataOperatorKey;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        PlatformOperatorService.MutationResult administrator = operatorService.bootstrap(
                BOOTSTRAP_TOKEN, "restart-admin-bootstrap", "Restart administrator",
                "restart-admin-request");
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administrator.credential(), "restart-data-operator", "Restart data operator",
                PlatformOperatorRole.DATA_OPERATOR, "restart-data-request");
        dataOperatorKey = dataOperator.credential();
    }

    /**
     * The row's failure sequence, first clause: process loss must not strand a RUNNING rebuild
     * forever. The scan must find it once its lease expires, and the resumed run must start from
     * the last committed checkpoint rather than re-reading committed work.
     */
    @Test
    void aRebuildStrandedByProcessLossIsFoundByTheScanAndResumesFromItsCheckpoint() {
        RebuildFixture fixture = rebuildWith(INPUT_COUNT, "stranded-rebuild");

        strandAfterFirstChunk(fixture.rebuildId());
        dispatcher.launchSynchronously(fixture.rebuildId());

        // Chunk one is committed and the row is RUNNING under an owner that will never return.
        // This is the exact state a killed JVM leaves; nothing recovers it while the lease is live.
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("RUNNING");
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(100);
        assertThat(checkpointKey(fixture.rebuildId())).isNotNull();
        assertThat(store.recoverable(10))
                .describedAs("a live lease must not be reclaimed, however dead its owner")
                .doesNotContain(fixture.rebuildId());

        expireLease(fixture.rebuildId());

        // Now the durable queue scan owns the recovery.
        assertThat(store.recoverable(10)).contains(fixture.rebuildId());
        dispatcher.launchSynchronously(fixture.rebuildId());

        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(INPUT_COUNT);
        assertThat(chunkCommitProgress(fixture.rebuildId()))
                .containsExactly(100L, (long) INPUT_COUNT);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(distinctProjectedPayments(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("CATCHING_UP");
        assertThat(eventHistory(fixture.rebuildId())).contains("LEASE_RECOVERED");
    }

    /**
     * Once resumed and complete, the rebuild must leave the scan. A scan that keeps returning
     * finished work would have a recovering deployment relaunch it forever.
     */
    @Test
    void aRecoveredRebuildLeavesTheScanAndCannotBeClaimedTwice() {
        RebuildFixture fixture = rebuildWith(2, "scan-idempotence");

        assertThat(store.recoverable(10)).contains(fixture.rebuildId());
        dispatcher.launchSynchronously(fixture.rebuildId());

        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("CATCHING_UP");
        assertThat(store.recoverable(10)).doesNotContain(fixture.rebuildId());
        assertThat(store.claim(fixture.rebuildId(), "second-claimant")).isNull();

        // Repeated scans are the steady state of a restarted deployment, so they must be free
        // of effect rather than merely harmless once.
        long epochAfterBuild = fencingEpoch(fixture.rebuildId());
        for (int scan = 0; scan < 3; scan++) {
            assertThat(store.recoverable(10)).doesNotContain(fixture.rebuildId());
        }
        assertThat(fencingEpoch(fixture.rebuildId())).isEqualTo(epochAfterBuild);
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(2);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(2);
    }

    /**
     * Disabling the worker is a deployment action, not a destructive one. Queued work must
     * survive it untouched and readiness must say so, so that an operator can tell a disabled
     * deployment from a stuck one.
     *
     * <p>The disabled dispatcher and health indicator are constructed directly rather than
     * through a second Spring context. They are the production classes with production
     * collaborators; only the flag differs, which is the whole point of the test.
     */
    @Test
    void disablingTheWorkerPreservesQueuedWorkAndIsVisibleInReadiness() {
        RebuildFixture fixture = rebuildWith(2, "worker-disabled");
        long queuedEpoch = fencingEpoch(fixture.rebuildId());

        ProjectionRebuildDispatcher disabled = new ProjectionRebuildDispatcher(
                jobLauncher, shadowBuildJob, store, rebuildExecutor, false, metrics);
        disabled.dispatch(fixture.rebuildId());

        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("QUEUED");
        assertThat(fencingEpoch(fixture.rebuildId())).isEqualTo(queuedEpoch);
        assertThat(processedInputCount(fixture.rebuildId())).isZero();
        assertThat(leaseOwner(fixture.rebuildId())).isNull();
        assertThat(eventHistory(fixture.rebuildId())).containsExactly("QUEUED");
        assertThat(projectionRowCount(fixture.generationId())).isZero();
        assertThat(generationStatus(fixture.generationId())).isEqualTo("BUILDING");

        assertThat(new ProjectionRebuildHealthIndicator(jdbcTemplate, clock, false).health())
                .satisfies(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
                    assertThat(health.getDetails()).containsEntry("state", "WORKER_DISABLED");
                });

        // Re-enabling is all the recovery a disabled deployment needs; the work is still there.
        dispatcher.launchSynchronously(fixture.rebuildId());
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("CATCHING_UP");
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(2);
    }

    /**
     * A fresh schema must install the rebuild substrate with exactly one active generation per
     * projection, and with the guards that make rebuild evidence append-only. An active
     * generation count of anything but one is the condition every reader depends on not seeing.
     */
    @Test
    void aFreshSchemaInstallsRebuildGuardsAndExactlyOneActiveGenerationPerProjection() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('69', '72', '73') AND success = TRUE
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                WHERE tgname = 'trg_projection_generations_protected' AND NOT tgisinternal
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT definition.projection_name FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                 AND generation.lifecycle_status = 'ACTIVE'
                ORDER BY definition.projection_name
                """, String.class)).containsExactly(
                        ProjectionNames.MERCHANT_RISK, ProjectionNames.PAYMENT_RISK);
    }

    /**
     * A realistic retained upgrade must carry prior source rows forward, install the rebuild
     * substrate, and invent no rebuild history. A migration that fabricated a completed rebuild
     * would hand readers a generation nothing ever built.
     */
    @Test
    void aRetainedUpgradePreservesPriorRowsAndFabricatesNoRebuildWork() {
        String schema = "rebuild_retained_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
            DriverManagerDataSource retainedDataSource = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .target("64").load().migrate();

            JdbcTemplate retained = new JdbcTemplate(retainedDataSource);
            UUID merchantId = UUID.randomUUID();
            retained.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at)
                    VALUES (?, 'retained-rebuild-merchant', 'ACTIVE', 'retained_', ?,
                        CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "a".repeat(64));

            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .load().migrate();

            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".merchants WHERE id = ?",
                    Integer.class, merchantId)).isEqualTo(1);
            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".projection_rebuilds",
                    Integer.class)).isZero();
            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".projection_rebuild_events",
                    Integer.class)).isZero();
            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".payment_risk_projections",
                    Integer.class)).isZero();
            // The upgrade installs the substrate, so the active pointer must be complete on the
            // retained path exactly as on the fresh one.
            assertThat(retained.queryForObject("""
                    SELECT COUNT(*) FROM %s.projection_definitions definition
                    JOIN %s.projection_generations generation
                      ON generation.id = definition.active_generation_id
                     AND generation.lifecycle_status = 'ACTIVE'
                    """.formatted(schema, schema), Integer.class)).isEqualTo(2);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /**
     * Strands the rebuild the way process loss does: chunk one commits, then the lease moves to
     * an owner that never returns, so the failing worker's own failure handler is fenced out and
     * cannot mark the rebuild FAILED. Without the epoch bump the listener would terminate it, and
     * a terminated rebuild is not recoverable — which is the distinction this suite turns on.
     */
    private void strandAfterFirstChunk(UUID rebuildId) {
        AtomicInteger strands = new AtomicInteger();
        doAnswer(invocation -> {
            if (strands.getAndIncrement() == 0) {
                TransactionTemplate separateProcess = new TransactionTemplate(transactionManager);
                separateProcess.setPropagationBehavior(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                separateProcess.executeWithoutResult(status -> jdbcTemplate.update("""
                        UPDATE projection_rebuilds
                           SET lease_owner = ?, lease_token = ?, fencing_epoch = fencing_epoch + 1
                         WHERE id = ?
                        """, "lost-process", UUID.randomUUID(), rebuildId));
            }
            return null;
        }).when(executionProbe).beforeChunkLease(any(), eq(2L));
    }

    /**
     * Ages the lease so the scan treats it as abandoned. {@code ck_projection_rebuilds_time}
     * enforces {@code lease_expires_at > updated_at}, so expiry is only reachable by winding
     * {@code updated_at} back with it, to an instant already in the past.
     */
    private void expireLease(UUID rebuildId) {
        Instant createdAt = jdbcTemplate.queryForObject("""
                SELECT created_at FROM projection_rebuilds WHERE id = ?
                """, Instant.class, rebuildId);
        jdbcTemplate.update("""
                UPDATE projection_rebuilds SET updated_at = ?, lease_expires_at = ? WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(1)),
                rebuildId);
    }

    private RebuildFixture rebuildWith(int inputCount, String label) {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant(label + "-merchant", label + "-api-key"));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), label + "-customer-hash", null));
        TransactionTemplate sourceTransaction = new TransactionTemplate(transactionManager);
        for (int index = 0; index < inputCount; index++) {
            PaymentIntent intent = paymentIntentRepository.saveAndFlush(
                    new PaymentIntent(merchant.getId(), customer.getId(), 1000L + index, "USD"));
            DomainEventWriteResult result = sourceTransaction.execute(status ->
                    eventWriter.append(createdCommand(merchant.getId(), intent)));
            assertThat(result).isNotNull();
        }
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-" + label, ProjectionNames.PAYMENT_RISK, "ALL",
                "start-" + label + "-request");
        assertThat(started.expectedInputCount()).isEqualTo(inputCount);
        return new RebuildFixture(started.rebuildId(), started.generationId());
    }

    private DomainEventCommand createdCommand(UUID merchantId, PaymentIntent intent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", intent.getAmount());
        payload.put("currency", intent.getCurrency());
        payload.put("captureMethod", intent.getCaptureMethod().name());
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, intent.getId(),
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, Instant.now().minusSeconds(1),
                "restart-correlation", null, "restart-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "restart-producer"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private long processedInputCount(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT processed_input_count FROM projection_rebuilds WHERE id = ?
                """, Long.class, rebuildId);
    }

    private long fencingEpoch(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT fencing_epoch FROM projection_rebuilds WHERE id = ?
                """, Long.class, rebuildId);
    }

    private String checkpointKey(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT last_checkpoint_key FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
    }

    private String lifecycleStatus(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
    }

    private String leaseOwner(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT lease_owner FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
    }

    private String generationStatus(UUID generationId) {
        return jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM projection_generations WHERE id = ?
                """, String.class, generationId);
    }

    private int projectionRowCount(UUID generationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ?
                """, Integer.class, generationId);
    }

    private int distinctProjectedPayments(UUID generationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT payment_intent_id) FROM payment_risk_projections
                WHERE projection_generation_id = ?
                """, Integer.class, generationId);
    }

    private List<Long> chunkCommitProgress(UUID rebuildId) {
        return jdbcTemplate.queryForList("""
                SELECT processed_input_count FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? AND event_type = 'CHUNK_COMMITTED'
                ORDER BY event_sequence
                """, Long.class, rebuildId);
    }

    private List<String> eventHistory(UUID rebuildId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? ORDER BY event_sequence
                """, String.class, rebuildId);
    }

    private record RebuildFixture(UUID rebuildId, UUID generationId) {
    }
}
