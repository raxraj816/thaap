package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.support.ApplicationRestartHarness;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C50-OPS-001} — the process-restart half.
 *
 * <p>This is a real application stop and start. {@link ApplicationRestartHarness} builds a
 * complete application context, the rebuild is driven until a worker is genuinely mid-chunk, and
 * then that context is destroyed with the worker still inside it. No SQL stages the aftermath:
 * the lease, the checkpoint and the orphaned {@code RUNNING} row are whatever the dead instance
 * actually left. A second instance is then started against the same database and asked to
 * recover, which is what a rolling deployment or a crash-restart does.
 *
 * <p>Building this closed the last substitution in the row. The sibling suite
 * {@link ProjectionRebuildRestartIntegrationTests} proves the recovery mechanism in detail
 * against a single context; this one proves the mechanism survives the event it exists for.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProjectionRebuildProcessRestartPostgreSqlTests {

    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";
    private static final int INPUT_COUNT = 101;

    /** Set false before the surviving instance starts, so only the dying one halts. */
    private static final AtomicBoolean HALT_AT_SECOND_CHUNK = new AtomicBoolean(true);
    private static final CountDownLatch REACHED_SECOND_CHUNK = new CountDownLatch(1);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private ApplicationRestartHarness harness;
    private JdbcTemplate outsideAnyInstance;

    @BeforeEach
    void setUp() {
        HALT_AT_SECOND_CHUNK.set(true);
        harness = new ApplicationRestartHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).withConfiguration(HaltingProbeConfiguration.class);
        outsideAnyInstance = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void aRebuildSurvivesTheDeathOfItsProcessAndResumesInTheNextInstance() throws Exception {
        // The worker switch is off so the only run is the one this test drives. Left on, the
        // after-commit dispatch listener races an async run against it and the instance is
        // killed at an instant the test did not choose.
        ConfigurableApplicationContext dying = harness.start(
                "thaap.projection-rebuild.worker-enabled=false");
        dying.getBean(DatabaseCleaner.class).clean();
        Fixture fixture = seedRebuild(dying);

        // Drive the rebuild on its own thread so the test thread can kill the instance while the
        // worker is still inside chunk two.
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ProjectionRebuildDispatcher dispatcher =
                    dying.getBean(ProjectionRebuildDispatcher.class);
            worker.submit(() -> dispatcher.launchSynchronously(fixture.rebuildId()));
            assertThat(REACHED_SECOND_CHUNK.await(30, TimeUnit.SECONDS))
                    .describedAs("the worker never reached the second chunk")
                    .isTrue();

            // Chunk one is committed and the process still owns the lease. This is the state
            // the row's failure sequence describes, produced rather than staged.
            assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(100);
            assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("RUNNING");
            assertThat(checkpointKey(fixture.rebuildId())).isNotNull();

            harness.stop();
        } finally {
            worker.shutdownNow();
        }

        // Nothing is running anywhere now. The rebuild is stranded exactly as it was left.
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("RUNNING");
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(100);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(100);

        // The lease is aged rather than waited out; sixty seconds of real time would prove the
        // same thing and cost sixty seconds. Recovery itself is the instance's own work.
        expireLease(fixture.rebuildId());

        HALT_AT_SECOND_CHUNK.set(false);
        ConfigurableApplicationContext surviving = harness.start(
                "thaap.projection-rebuild.worker-enabled=false");
        ProjectionRebuildStore store = surviving.getBean(ProjectionRebuildStore.class);
        assertThat(store.recoverable(10)).contains(fixture.rebuildId());
        surviving.getBean(ProjectionRebuildDispatcher.class)
                .launchSynchronously(fixture.rebuildId());

        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(INPUT_COUNT);
        assertThat(chunkCommitProgress(fixture.rebuildId()))
                .describedAs("the surviving instance re-read committed work")
                .containsExactly(100L, (long) INPUT_COUNT);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(distinctProjectedPayments(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("CATCHING_UP");
        assertThat(eventHistory(fixture.rebuildId())).contains("LEASE_RECOVERED");
    }

    private Fixture seedRebuild(ConfigurableApplicationContext context) {
        PlatformOperatorService operators = context.getBean(PlatformOperatorService.class);
        PlatformOperatorService.MutationResult administrator = operators.bootstrap(
                BOOTSTRAP_TOKEN, "process-restart-admin", "Process restart administrator",
                "process-restart-admin-request");
        String dataOperatorKey = operators.create(administrator.credential(),
                "process-restart-operator", "Process restart operator",
                PlatformOperatorRole.DATA_OPERATOR, "process-restart-request").credential();

        Merchant merchant = context.getBean(MerchantRepository.class).saveAndFlush(
                new Merchant("process-restart-merchant", "process-restart-api-key"));
        Customer customer = context.getBean(CustomerRepository.class).saveAndFlush(
                new Customer(merchant.getId(), "process-restart-customer", null));
        PaymentIntentRepository intents = context.getBean(PaymentIntentRepository.class);
        DomainEventWriter writer = context.getBean(DomainEventWriter.class);
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        TransactionTemplate transaction = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
        for (int index = 0; index < INPUT_COUNT; index++) {
            PaymentIntent intent = intents.saveAndFlush(new PaymentIntent(
                    merchant.getId(), customer.getId(), 1000L + index, "USD"));
            transaction.execute(status -> writer.append(
                    createdCommand(objectMapper, merchant.getId(), intent)));
        }

        ProjectionRebuildControlService.RebuildView started =
                context.getBean(ProjectionRebuildControlService.class).start(dataOperatorKey,
                        "start-process-restart", ProjectionNames.PAYMENT_RISK, "ALL",
                        "start-process-restart-request");
        assertThat(started.expectedInputCount()).isEqualTo(INPUT_COUNT);
        return new Fixture(started.rebuildId(), started.generationId());
    }

    private DomainEventCommand createdCommand(ObjectMapper objectMapper, UUID merchantId,
            PaymentIntent intent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", intent.getAmount());
        payload.put("currency", intent.getCurrency());
        payload.put("captureMethod", intent.getCaptureMethod().name());
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, intent.getId(),
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, Instant.now().minusSeconds(1),
                "process-restart-correlation", null, "process-restart-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "process-restart-producer"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private void expireLease(UUID rebuildId) {
        Instant createdAt = outsideAnyInstance.queryForObject("""
                SELECT created_at FROM projection_rebuilds WHERE id = ?
                """, Instant.class, rebuildId);
        outsideAnyInstance.update("""
                UPDATE projection_rebuilds SET updated_at = ?, lease_expires_at = ? WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(1)),
                rebuildId);
    }

    private long processedInputCount(UUID rebuildId) {
        return outsideAnyInstance.queryForObject("""
                SELECT processed_input_count FROM projection_rebuilds WHERE id = ?
                """, Long.class, rebuildId);
    }

    private String lifecycleStatus(UUID rebuildId) {
        return outsideAnyInstance.queryForObject("""
                SELECT lifecycle_status FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
    }

    private String checkpointKey(UUID rebuildId) {
        return outsideAnyInstance.queryForObject("""
                SELECT last_checkpoint_key FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
    }

    private int projectionRowCount(UUID generationId) {
        return outsideAnyInstance.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections WHERE projection_generation_id = ?
                """, Integer.class, generationId);
    }

    private int distinctProjectedPayments(UUID generationId) {
        return outsideAnyInstance.queryForObject("""
                SELECT COUNT(DISTINCT payment_intent_id) FROM payment_risk_projections
                WHERE projection_generation_id = ?
                """, Integer.class, generationId);
    }

    private List<Long> chunkCommitProgress(UUID rebuildId) {
        return outsideAnyInstance.queryForList("""
                SELECT processed_input_count FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? AND event_type = 'CHUNK_COMMITTED'
                ORDER BY event_sequence
                """, Long.class, rebuildId);
    }

    private List<String> eventHistory(UUID rebuildId) {
        return outsideAnyInstance.queryForList("""
                SELECT event_type FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? ORDER BY event_sequence
                """, String.class, rebuildId);
    }

    /**
     * Installs a probe that parks the worker inside chunk two so the instance can be killed with
     * a rebuild genuinely in flight. The seam lives inside the application under test because
     * that is the only place from which a worker can be caught mid-chunk.
     */
    @TestConfiguration
    static class HaltingProbeConfiguration {

        @Bean
        @Primary
        ProjectionRebuildExecutionProbe haltingProbe() {
            return new ProjectionRebuildExecutionProbe() {
                @Override
                public void beforeChunkLease(UUID rebuildId, long chunkOrdinal) {
                    if (chunkOrdinal != 2 || !HALT_AT_SECOND_CHUNK.get()) {
                        return;
                    }
                    REACHED_SECOND_CHUNK.countDown();
                    try {
                        // Parked until the instance around it is destroyed.
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    private record Fixture(UUID rebuildId, UUID generationId) { }
}
