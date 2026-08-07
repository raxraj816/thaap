package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Proof for {@code C50-CON-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}). What made it
 * payable was {@link ProjectionRebuildExecutionProbe}: the rebuild lease invariants are only
 * observable at instants inside a Spring Batch chunk, and until the seam existed a test could
 * only assert the outcome of an uninterrupted run. Replay had the equivalent seam from the
 * start, which is why {@code C51-CON-001} was partially payable and this row was not.
 *
 * <p>These run against real PostgreSQL because every claim here is about row locks, a
 * compare-and-update fence, and transaction boundaries. None of it means anything on an
 * in-memory database.
 *
 * <p>Takeovers are forced by bumping the fencing epoch directly rather than by ageing the
 * lease and racing a re-claim. Expiry is judged against the wall clock; fencing is a pure
 * function of the epoch. The lesson is inherited from the replay suite, where the clock-based
 * version of this test was flaky.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProjectionRebuildLeasePostgreSqlConcurrencyTests {

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
    @Autowired private PlatformOperatorService operatorService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @MockitoSpyBean private ProjectionRebuildExecutionProbe executionProbe;

    private String dataOperatorKey;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        PlatformOperatorService.MutationResult administrator = operatorService.bootstrap(
                BOOTSTRAP_TOKEN, "lease-race-admin-bootstrap", "Lease race administrator",
                "lease-race-admin-request");
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administrator.credential(), "lease-race-data-operator",
                "Lease race data operator", PlatformOperatorRole.DATA_OPERATOR,
                "lease-race-data-request");
        dataOperatorKey = dataOperator.credential();
    }

    /**
     * The row's exact failure sequence: worker A pauses, worker B takes over, A resumes and
     * tries to write. A's write must be refused, B's progress must survive, and A's own
     * failure handling must not mark a rebuild it no longer owns.
     *
     * <p>The takeover is forced at {@code beforeChunkLease}, the one instant where the worker
     * intends to write and holds no lock on the rebuild row. Every later point is inside the
     * chunk transaction, which opens with {@code SELECT ... FOR UPDATE} on that row, so a
     * takeover attempted there would block on the victim rather than race it.
     */
    @Test
    void aStaleWorkerCannotWriteCompleteOrFailARebuildAfterLeaseTakeover() {
        RebuildFixture fixture = rebuildWith(INPUT_COUNT, "stale-worker");
        long claimedEpoch = jdbcTemplate.queryForObject("""
                SELECT fencing_epoch FROM projection_rebuilds WHERE id = ?
                """, Long.class, fixture.rebuildId());

        // Take the lease away between chunk one and chunk two. Chunk one is durably
        // committed at this point; chunk two has read its items and is about to claim the
        // lease it no longer holds.
        AtomicInteger takeovers = new AtomicInteger();
        doAnswer(invocation -> {
            if (takeovers.getAndIncrement() == 0) {
                takeOverLease(fixture.rebuildId(), "recovering-worker");
            }
            return null;
        }).when(executionProbe).beforeChunkLease(any(), eq(2L));

        dispatcher.launchSynchronously(fixture.rebuildId());
        assertThat(takeovers.get()).isEqualTo(1);

        // Chunk one survives; chunk two wrote nothing. The fence is what refused it, not a
        // uniqueness constraint downstream: the writer never reached the applier.
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(100);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(100);
        assertThat(streamPositionCount(fixture.generationId())).isEqualTo(100);
        assertThat(chunkCommitProgress(fixture.rebuildId())).containsExactly(100L);

        // The stale worker's job failure must not mark the rebuild FAILED. failIfCurrent is
        // fenced on token and epoch, so the row stays RUNNING under its new owner. Without
        // that fence a dead worker would terminate a rebuild another worker is running.
        long currentEpoch = jdbcTemplate.queryForObject("""
                SELECT fencing_epoch FROM projection_rebuilds WHERE id = ?
                """, Long.class, fixture.rebuildId());
        assertThat(currentEpoch).isGreaterThan(claimedEpoch);
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("RUNNING");
        assertThat(leaseOwner(fixture.rebuildId())).isEqualTo("recovering-worker");
        assertThat(failureCode(fixture.rebuildId())).isNull();
        assertThat(generationStatus(fixture.generationId())).isEqualTo("BUILDING");

        // The stale lease is still syntactically valid. Only the epoch tells it apart, which
        // is why it is checked in the same locked statement that reads the row.
        ProjectionRebuildLease stale = new ProjectionRebuildLease(fixture.rebuildId(),
                fixture.definitionId(), fixture.generationId(), fixture.cutoffManifestId(),
                ProjectionNames.PAYMENT_RISK, handlerVersion(), "stale-worker",
                leaseToken(fixture.rebuildId()), claimedEpoch, 100, INPUT_COUNT,
                checkpointKey(fixture.rebuildId()), Instant.now().plusSeconds(60), false);
        // The fence surfaces as itself, not wrapped. The store is a @Repository, so anything
        // it throws passes through Spring's persistence exception translation; the fence type
        // extends RuntimeException specifically so it survives that intact and a caller can
        // catch it. Asserting the exact type here is what keeps that property from being
        // undone by a later change to the exception's supertype.
        assertThatThrownBy(() -> store.commitChunk(stale, 1, checkpointKey(fixture.rebuildId())))
                .isExactlyInstanceOf(ProjectionRebuildFenceException.class);
        assertThatThrownBy(() -> store.finishBuild(fixture.rebuildId(),
                stale.leaseToken(), claimedEpoch))
                .isExactlyInstanceOf(ProjectionRebuildFenceException.class);
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(100);
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("RUNNING");
    }

    /**
     * The other half of the same sequence: after a takeover, the rebuild must resume from the
     * last committed checkpoint rather than from the beginning, and the 100 inputs chunk one
     * already committed must not be counted or applied twice.
     */
    @Test
    void aRecoveredLeaseResumesFromTheLastCommittedCheckpointWithoutDoubleCounting() {
        RebuildFixture fixture = rebuildWith(INPUT_COUNT, "resume-worker");

        AtomicInteger takeovers = new AtomicInteger();
        doAnswer(invocation -> {
            if (takeovers.getAndIncrement() == 0) {
                takeOverLease(fixture.rebuildId(), "abandoned-worker");
            }
            return null;
        }).when(executionProbe).beforeChunkLease(any(), eq(2L));

        dispatcher.launchSynchronously(fixture.rebuildId());
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(100);
        String checkpointAfterFirstChunk = checkpointKey(fixture.rebuildId());
        assertThat(checkpointAfterFirstChunk).isNotNull();

        // Expire the abandoned lease so the next claim is a recovery rather than a fresh
        // claim, then run the real job again. The reader reopens on the stored checkpoint.
        expireLease(fixture.rebuildId());
        dispatcher.launchSynchronously(fixture.rebuildId());

        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(INPUT_COUNT);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(streamPositionCount(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(chunkCommitProgress(fixture.rebuildId()))
                .containsExactly(100L, (long) INPUT_COUNT);
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("CATCHING_UP");

        // Recovery is recorded as recovery, with the reason an operator needs to see. A
        // higher epoch alone does not say a worker died mid-rebuild.
        assertThat(leaseEventHistory(fixture.rebuildId()))
                .containsSubsequence("LEASED", "CHUNK_COMMITTED", "LEASE_RECOVERED",
                        "CHUNK_COMMITTED", "CATCH_UP_STARTED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT safe_reason_code FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? AND event_type = 'LEASE_RECOVERED'
                """, String.class, fixture.rebuildId())).containsExactly("EXPIRED_LEASE");

        // The counts above would also be satisfied by a resumed build that reapplied chunk
        // one over itself, so the shape of the result is asserted too: one row per payment
        // stream, each at exactly the one version its stream has. Reapplication would either
        // duplicate a row or advance a version past its source.
        assertThat(distinctProjectedPayments(fixture.generationId())).isEqualTo(INPUT_COUNT);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_stream_positions
                WHERE projection_generation_id = ? AND last_stream_version <> 1
                """, Integer.class, fixture.generationId())).isZero();
    }

    /**
     * A crash after a chunk's evidence rows are written and before its checkpoint advances
     * must leave nothing behind. Evidence and checkpoint share one transaction precisely so
     * that a resumed worker cannot re-read the same page and count it twice.
     */
    @Test
    void aCrashBetweenChunkEvidenceAndCheckpointLeavesNoPartialChunk() {
        RebuildFixture fixture = rebuildWith(INPUT_COUNT, "crash-worker");

        AtomicInteger crashes = new AtomicInteger();
        doAnswer(invocation -> {
            if (crashes.getAndIncrement() == 0) {
                throw new IllegalStateException("forced crash before checkpoint");
            }
            return null;
        }).when(executionProbe).afterChunkEvidenceBeforeCheckpoint(any(), anyLong());

        dispatcher.launchSynchronously(fixture.rebuildId());
        assertThat(crashes.get()).isEqualTo(1);

        assertThat(processedInputCount(fixture.rebuildId())).isZero();
        assertThat(checkpointKey(fixture.rebuildId())).isNull();
        assertThat(projectionRowCount(fixture.generationId())).isZero();
        assertThat(streamPositionCount(fixture.generationId())).isZero();
        assertThat(chunkCommitProgress(fixture.rebuildId())).isEmpty();

        // Here the epoch still matches, so the failure handler is the current owner and the
        // rebuild is correctly terminated with a released lease. This is the contrast case
        // for the stale-worker test: the same code path, opposite outcome, decided by the
        // epoch alone.
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("FAILED");
        assertThat(failureCode(fixture.rebuildId())).isEqualTo("REBUILD_EXECUTION_FAILED");
        assertThat(leaseOwner(fixture.rebuildId())).isNull();
        assertThat(leaseToken(fixture.rebuildId())).isNull();
        assertThat(generationStatus(fixture.generationId())).isEqualTo("FAILED");
    }

    /**
     * A crash after the checkpoint statement but before the chunk transaction commits must
     * also leave nothing behind. The checkpoint is not durable because it was written; it is
     * durable because its transaction committed.
     */
    @Test
    void aCrashAfterTheCheckpointStatementButBeforeCommitLeavesNoPartialChunk() {
        RebuildFixture fixture = rebuildWith(INPUT_COUNT, "uncommitted-worker");

        AtomicInteger crashes = new AtomicInteger();
        doAnswer(invocation -> {
            if (crashes.getAndIncrement() == 0) {
                throw new IllegalStateException("forced crash after checkpoint statement");
            }
            return null;
        }).when(executionProbe).afterChunkCheckpoint(any(), anyLong());

        dispatcher.launchSynchronously(fixture.rebuildId());
        assertThat(crashes.get()).isEqualTo(1);

        assertThat(processedInputCount(fixture.rebuildId())).isZero();
        assertThat(checkpointKey(fixture.rebuildId())).isNull();
        assertThat(projectionRowCount(fixture.generationId())).isZero();
        assertThat(chunkCommitProgress(fixture.rebuildId())).isEmpty();
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("FAILED");
    }

    /**
     * Only one worker may hold a rebuild. Two simultaneous claims on the same queued rebuild
     * must produce exactly one lease and exactly one leased-event, and the epoch must move
     * once. This is the claim path's serialization, which the takeover tests assume.
     */
    @Test
    void twoSimultaneousClaimsOnOneQueuedRebuildYieldExactlyOneLease() throws Exception {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-claim-race", ProjectionNames.PAYMENT_RISK,
                "ALL", "start-claim-race-request");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectionRebuildLease> first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.claim(started.rebuildId(), "claim-race-worker-a");
            });
            Future<ProjectionRebuildLease> second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.claim(started.rebuildId(), "claim-race-worker-b");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ProjectionRebuildLease> leases = java.util.stream.Stream
                    .of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(leases).hasSize(1);
            assertThat(leases.getFirst().recovered()).isFalse();
            assertThat(leases.getFirst().fencingEpoch()).isEqualTo(1);
            assertThat(leaseOwner(started.rebuildId()))
                    .isEqualTo(leases.getFirst().workerId());
            assertThat(leaseEventHistory(started.rebuildId()))
                    .containsExactly("QUEUED", "LEASED");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * The chunk boundary is exactly 100, so 99 inputs must commit as one partial chunk and
     * never as a full one. This is the partition immediately below the boundary, where a
     * fencepost error in the chunk-size guard would land.
     */
    @Test
    void ninetyNineInputsCommitAsExactlyOnePartialChunk() {
        RebuildFixture fixture = rebuildWith(99, "single-partial-chunk");

        dispatcher.launchSynchronously(fixture.rebuildId());

        assertThat(chunkCommitProgress(fixture.rebuildId())).containsExactly(99L);
        assertThat(processedInputCount(fixture.rebuildId())).isEqualTo(99);
        assertThat(projectionRowCount(fixture.generationId())).isEqualTo(99);
        assertThat(lifecycleStatus(fixture.rebuildId())).isEqualTo("CATCHING_UP");
    }

    /**
     * The claim path must yield exactly one lease whichever contender reaches the row first,
     * in both orders. The order is forced rather than raced: the test holds the rebuild row,
     * queues the two workers behind a lock it controls, and releases it. Racing them and
     * running the test repeatedly would sample both schedules eventually; this asserts each
     * one on purpose.
     */
    @Test
    void whicheverWorkerReachesTheRowFirstIsTheOnlyOneToHoldTheLease() throws Exception {
        // The two orderings use different projection definitions because a definition
        // refuses a second rebuild while one is unfinished, and the first ordering leaves
        // its rebuild RUNNING under the worker that won.
        assertExactlyOneLeaseUnderForcedOrder("forced-order-a", ProjectionNames.PAYMENT_RISK,
                "worker-a", "worker-b");
        assertExactlyOneLeaseUnderForcedOrder("forced-order-b", ProjectionNames.MERCHANT_RISK,
                "worker-b", "worker-a");
    }

    private void assertExactlyOneLeaseUnderForcedOrder(String label, String projectionName,
            String firstWorker, String secondWorker) throws Exception {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "start-" + label, projectionName, "ALL",
                "start-" + label + "-request");

        CountDownLatch rowHeld = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            executor.submit(() -> {
                TransactionTemplate holder = new TransactionTemplate(transactionManager);
                holder.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                holder.executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject("""
                            SELECT fencing_epoch FROM projection_rebuilds
                            WHERE id = ? FOR UPDATE
                            """, Long.class, started.rebuildId());
                    rowHeld.countDown();
                    try {
                        releaseRow.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
            assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ProjectionRebuildLease> first = executor.submit(
                    () -> store.claim(started.rebuildId(), firstWorker));
            // The stagger is what makes the order deterministic: the first claim is already
            // waiting on the row lock before the second one asks for it, and PostgreSQL
            // grants waiters in arrival order.
            Thread.sleep(250);
            Future<ProjectionRebuildLease> second = executor.submit(
                    () -> store.claim(started.rebuildId(), secondWorker));
            Thread.sleep(250);
            releaseRow.countDown();

            List<ProjectionRebuildLease> leases = java.util.stream.Stream
                    .of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(leases).hasSize(1);
            assertThat(leases.getFirst().workerId()).isEqualTo(firstWorker);
            assertThat(leases.getFirst().fencingEpoch()).isEqualTo(1);
            assertThat(leaseOwner(started.rebuildId())).isEqualTo(firstWorker);
            assertThat(leaseEventHistory(started.rebuildId()))
                    .containsExactly("QUEUED", "LEASED");
        } finally {
            releaseRow.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Simulates another worker winning the lease, without depending on the wall clock.
     *
     * <p>Runs in its own transaction. The probe fires inside the victim's chunk transaction,
     * and a plain {@code JdbcTemplate} call there would join that transaction and be rolled
     * back along with the chunk it is supposed to have fenced — the takeover would vanish and
     * the test would silently assert nothing. Another worker is another transaction, so this
     * one is too. It commits before the victim reaches {@code requireCurrentLease}, which is
     * what makes the fence observable.
     */
    private void takeOverLease(UUID rebuildId, String workerId) {
        TransactionTemplate separateWorker = new TransactionTemplate(transactionManager);
        separateWorker.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        separateWorker.executeWithoutResult(status -> {
            int changed = jdbcTemplate.update("""
                    UPDATE projection_rebuilds
                       SET lease_owner = ?, lease_token = ?, fencing_epoch = fencing_epoch + 1
                     WHERE id = ?
                    """, workerId, UUID.randomUUID(), rebuildId);
            if (changed != 1) {
                throw new IllegalStateException("Lease takeover fixture did not apply");
            }
        });
    }

    /**
     * Ages the lease so the next claim is a recovery rather than a fresh claim.
     *
     * <p>Anchored on {@code created_at}, which precedes a start transaction and a claim
     * transaction and is therefore decisively in the past. Anchoring on {@code updated_at}
     * leaves only the microseconds since the last write and races the wall clock.
     */
    private void expireLease(UUID rebuildId) {
        Instant createdAt = jdbcTemplate.queryForObject("""
                SELECT created_at FROM projection_rebuilds WHERE id = ?
                """, Instant.class, rebuildId);
        // ck_projection_rebuilds_time enforces lease_expires_at > updated_at, so a lease
        // cannot simply be back-dated: the schema does not permit an already-expired lease
        // to be written. Expiry is only reachable by winding updated_at back with it and
        // ending the lease immediately after, at an instant already in the past.
        jdbcTemplate.update("""
                UPDATE projection_rebuilds SET updated_at = ?, lease_expires_at = ?
                WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(1)),
                rebuildId);
    }

    /**
     * Seeds {@code inputCount} payment streams and starts a rebuild over them, so the build
     * spans one full 100-input chunk and one partial chunk.
     */
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
        return new RebuildFixture(started.rebuildId(), started.generationId(),
                started.cutoffManifestId(), definitionId());
    }

    private DomainEventCommand createdCommand(UUID merchantId, PaymentIntent intent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", intent.getAmount());
        payload.put("currency", intent.getCurrency());
        payload.put("captureMethod", intent.getCaptureMethod().name());
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, intent.getId(),
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, Instant.now().minusSeconds(1),
                "lease-race-correlation", null, "lease-race-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "lease-race-producer"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private UUID definitionId() {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM projection_definitions WHERE projection_name = ?
                """, UUID.class, ProjectionNames.PAYMENT_RISK);
    }

    private String handlerVersion() {
        return jdbcTemplate.queryForObject("""
                SELECT handler_version FROM projection_definitions WHERE projection_name = ?
                """, String.class, ProjectionNames.PAYMENT_RISK);
    }

    private long processedInputCount(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT processed_input_count FROM projection_rebuilds WHERE id = ?
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

    private UUID leaseToken(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT lease_token FROM projection_rebuilds WHERE id = ?
                """, UUID.class, rebuildId);
    }

    private String failureCode(UUID rebuildId) {
        return jdbcTemplate.queryForObject("""
                SELECT failure_code FROM projection_rebuilds WHERE id = ?
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

    private int streamPositionCount(UUID generationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_stream_positions
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

    private List<String> leaseEventHistory(UUID rebuildId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type FROM projection_rebuild_events
                WHERE projection_rebuild_id = ? ORDER BY event_sequence
                """, String.class, rebuildId);
    }

    private record RebuildFixture(UUID rebuildId, UUID generationId, UUID cutoffManifestId,
            UUID definitionId) {
    }
}
