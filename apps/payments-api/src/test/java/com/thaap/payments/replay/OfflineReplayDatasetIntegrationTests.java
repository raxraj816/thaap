package com.thaap.payments.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.replay.OfflineReplayDatasetService.ReplayDatasetView;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkOutcome;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.replay-datasets.creation-enabled=true",
        "thaap.replay-datasets.worker-enabled=false",
        "thaap.replay-datasets.chunk-size=1"
})
class OfflineReplayDatasetIntegrationTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private OfflineReplayDatasetWorker worker;
    @Autowired private OfflineReplayDatasetService service;
    @MockitoSpyBean private OfflineReplayDatasetExecutionProbe executionProbe;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void exactLabelCutoffIsIncludedAndFutureLabelDoesNotLeak() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("point-in-time-label");

        UUID beforeDataset = createDataset(fixture, fixture.labelAvailableAt().minus(1, ChronoUnit.MICROS),
                null, "replay-before-label");
        complete(beforeDataset);
        ReplayDatasetView before = service.get(fixture.manager().context(), beforeDataset);
        assertThat(before.coverage().processedCount()).isOne();
        assertThat(before.coverage().unlabeledCount()).isOne();
        assertThat(before.aggregates()).anySatisfy(aggregate -> {
            assertThat(aggregate.metricCode()).isEqualTo(ReplayDatasetMetricCode.LABEL_OUTCOME);
            assertThat(aggregate.bucketCode()).isEqualTo("UNLABELED:USD");
            assertThat(aggregate.populationCount()).isOne();
            assertThat(aggregate.amountMinor()).isNull();
        });

        UUID exactDataset = createDataset(fixture, fixture.labelAvailableAt(), null,
                "replay-exact-label");
        complete(exactDataset);
        ReplayDatasetView exact = service.get(fixture.manager().context(), exactDataset);
        assertThat(exact.coverage().unlabeledCount()).isZero();
        assertThat(exact.aggregates()).anySatisfy(aggregate -> {
            assertThat(aggregate.metricCode()).isEqualTo(ReplayDatasetMetricCode.LABEL_OUTCOME);
            assertThat(aggregate.bucketCode()).isEqualTo("FRAUD:USD");
            assertThat(aggregate.populationCount()).isOne();
        });
        assertThat(before.populationSha256()).isEqualTo(exact.populationSha256());
        assertThat(before.outputSha256()).isNotEqualTo(exact.outputSha256());
        assertThat(exact.compatibilityStatus())
                .isEqualTo(ReplayDatasetCompatibilityStatus.FAILED);
        assertThat(exact.compatibilityFailureCode()).isEqualTo("SIMULATION_NOT_BOUND");
    }

    @Test
    void governedSimulationOverlapMustMatchEveryDeclaredFamily() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("simulation-equivalence");
        UUID simulationId = insertMatchingSuccessfulSimulation(fixture);

        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), simulationId,
                "replay-compatible");
        complete(datasetId);

        ReplayDatasetView dataset = service.get(fixture.manager().context(), datasetId);
        assertThat(dataset.lifecycleStatus()).isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
        assertThat(dataset.compatibilityStatus()).isEqualTo(ReplayDatasetCompatibilityStatus.PASSED);
        assertThat(dataset.compatibilityFailureCode()).isNull();
        assertThat(dataset.compatibilitySha256()).hasSize(64);
        assertThat(dataset.aggregates().stream()
                .filter(result -> result.metricCode()
                        == ReplayDatasetMetricCode.SIMULATION_EQUIVALENCE))
                .hasSize(7)
                .allSatisfy(result -> assertThat(result.bucketCode()).endsWith(":PASSED"));
    }

    @Test
    void sameTimestampDecisionCannotBorrowAdjacentFraudEventCutoffMembership()
            throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("exact-event-membership");
        UnlinkedDecision collision = insertUnlinkedDecisionAtSameTimestamp(fixture);

        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "replay-exact-event-membership");
        complete(datasetId);

        ReplayDatasetView dataset = service.get(fixture.manager().context(), datasetId);
        assertThat(dataset.coverage().processedCount()).isEqualTo(2);
        assertThat(dataset.coverage().corruptLineageCount()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_source_lineage
                WHERE merchant_id = ? AND fraud_decision_id = ?
                """, Integer.class, fixture.merchant().id(), fixture.decisionId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_source_lineage
                WHERE merchant_id = ? AND fraud_decision_id = ?
                """, Integer.class, fixture.merchant().id(), collision.decisionId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM fraud_decisions WHERE id = ?", Instant.class,
                collision.decisionId())).isEqualTo(fixture.decisionCreatedAt());
    }

    @Test
    void apiIsActorIdempotentRoleBoundAndTenantOpaque() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("api-boundary");
        OperatorAccess viewer = createOperator(fixture.merchant(), "VIEWER", "replay-viewer");
        MerchantAccess foreign = createMerchant("replay-foreign");
        OperatorAccess foreignManager = createOperator(foreign, "RISK_MANAGER",
                "replay-foreign-manager");
        String body = datasetRequest(fixture, fixture.labelAvailableAt(), null);

        MvcResult first = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, "replay-api-create")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, "replay-api-create")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        UUID datasetId = UUID.fromString(objectMapper.readTree(
                first.getResponse().getContentAsString()).get("datasetId").asText());

        mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, viewer.secret())
                        .header(IDEMPOTENCY_KEY, "replay-viewer-create")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/risk/replay-datasets/{id}", datasetId)
                        .header(RISK_KEY, foreignManager.secret()))
                .andExpect(status().isNotFound());
        String safe = mockMvc.perform(get("/risk/replay-datasets/{id}", datasetId)
                        .header(RISK_KEY, viewer.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(safe).doesNotContain("4242424242424242", "point-in-time@example.test",
                "leaseToken", "checkpointAttemptId", "featureValue");
    }

    /**
     * Proof for the creator-side half of {@code C51-CON-001}.
     *
     * <p>Two risk managers create over the same cohort at the same moment, under different
     * idempotency keys. They converge on one dataset rather than forking the cohort into two:
     * creation serializes on a per-merchant lock and dataset identity is derived from the frozen
     * cohort, not from who asked or when. Two datasets over one cohort would be two answers to
     * one question, and nothing downstream could say which was authoritative.
     */
    @Test
    void twoCreatorsRacingOverOneCohortConvergeOnOneWholeDataset() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("creator-race");
        OperatorAccess second = createOperator(fixture.merchant(), "RISK_MANAGER",
                "creator-race-second-manager");
        String body = datasetRequest(fixture, fixture.labelAvailableAt(), null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() ->
                    createConcurrently(fixture.manager().secret(), body, "creator-race-a",
                            ready, start));
            Future<String> other = executor.submit(() ->
                    createConcurrently(second.secret(), body, "creator-race-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            UUID firstDataset = UUID.fromString(objectMapper.readTree(
                    first.get(20, TimeUnit.SECONDS)).get("datasetId").asText());
            UUID otherDataset = UUID.fromString(objectMapper.readTree(
                    other.get(20, TimeUnit.SECONDS)).get("datasetId").asText());
            assertThat(otherDataset)
                    .describedAs("one cohort must not fork into two datasets")
                    .isEqualTo(firstDataset);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM replay_dataset_manifests", Integer.class)).isEqualTo(1);
        // Each manifest is whole: the lock released only committed work, never a half-built row.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM replay_dataset_manifests
                WHERE cutoff_manifest_id IS NULL OR projection_generation_id IS NULL
                   OR query_sha256 IS NULL OR lifecycle_status IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT lifecycle_status) FROM replay_dataset_manifests
                """, Integer.class)).isEqualTo(1);
    }

    /**
     * Proof for the cross-creator digest half of {@code C51-CON-001}.
     *
     * <p>A dataset is a function of its cohort and nothing else. Both managers resolve to the
     * same dataset, and reading it as either one yields the same digest, coverage, aggregates and
     * item-level membership. If the reader's identity could move any of those, no two datasets
     * would ever be comparable and the equivalence apparatus would be measuring noise.
     */
    @Test
    void theSameCohortReadByEitherCreatorYieldsTheSameDigestAndMembership() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("cross-creator-digest");
        OperatorAccess second = createOperator(fixture.merchant(), "RISK_MANAGER",
                "cross-creator-second-manager");
        String body = datasetRequest(fixture, fixture.labelAvailableAt(), null);

        UUID firstDataset = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/risk/replay-datasets")
                                .header(RISK_KEY, fixture.manager().secret())
                                .header(IDEMPOTENCY_KEY, "cross-creator-first")
                                .contentType(APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString()).get("datasetId").asText());
        UUID secondDataset = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/risk/replay-datasets").header(RISK_KEY, second.secret())
                                .header(IDEMPOTENCY_KEY, "cross-creator-second")
                                .contentType(APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString()).get("datasetId").asText());
        assertThat(secondDataset).isEqualTo(firstDataset);

        complete(firstDataset);
        ReplayDatasetView first = service.get(fixture.manager().context(), firstDataset);
        ReplayDatasetView other = service.get(second.context(), secondDataset);

        assertThat(first.lifecycleStatus()).isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
        assertThat(other.lifecycleStatus()).isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
        assertThat(other.outputSha256())
                .describedAs("the same dataset read differently for a different creator")
                .isEqualTo(first.outputSha256());
        assertThat(other.populationSha256()).isEqualTo(first.populationSha256());
        assertThat(other.coverage()).isEqualTo(first.coverage());
        assertThat(other.aggregates()).containsExactlyElementsOf(first.aggregates());
        // The second creation built nothing of its own: one set of item evidence exists, and the
        // digests above are over that one set rather than over two cohorts that happen to agree.
        assertThat(itemDigests(firstDataset)).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT replay_dataset_id) FROM replay_dataset_item_evidence
                """, Integer.class)).isEqualTo(1);
    }

    private String createConcurrently(String secret, String body, String idempotencyKey,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("creator race never started");
        }
        return mockMvc.perform(post("/risk/replay-datasets").header(RISK_KEY, secret)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private List<String> itemDigests(UUID datasetId) {
        return jdbcTemplate.queryForList("""
                SELECT item_sha256 FROM replay_dataset_item_evidence
                WHERE replay_dataset_id = ? ORDER BY item_ordinal
                """, String.class, datasetId);
    }

    private UUID createDataset(ReplayFixture fixture, Instant labelCutoff,
            UUID simulationId, String key) throws Exception {
        String response = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content(datasetRequest(fixture, labelCutoff, simulationId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("datasetId").asText());
    }

    /**
     * Proof for {@code C51-CON-001}.
     *
     * <p>{@link OfflineReplayDatasetExecutionProbe} has existed since the dataset worker was
     * built, as a declared "test seam for deterministic crash and stale-worker interleavings", wired
     * into the worker at both points — and no test ever used it, because the row naming
     * this proof closed on a suite nobody wrote ({@code ER-0099}, {@code TD-0007}).
     *
     * <p>These run against real PostgreSQL through {@code OfflineReplayDatasetPostgreSqlTests},
     * which is what makes the lock and fencing claims mean anything.
     */
    @Test
    void aCrashBetweenChunkEvidenceAndCheckpointResumesWithoutDoubleCounting() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("replay-crash-resume");
        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "replay-crash-resume");

        // Fail exactly once, after the chunk's evidence rows are written and before the
        // checkpoint advances. That window is the one where a resumed worker can re-read
        // the same page and count it twice.
        AtomicInteger crashes = new AtomicInteger();
        doAnswer(invocation -> {
            if (crashes.getAndIncrement() == 0) {
                throw new IllegalStateException("forced crash before checkpoint");
            }
            return null;
        }).when(executionProbe).afterChunkEvidenceBeforeCheckpoint(any(), anyLong());

        WorkerClaim first = worker.claim(datasetId, "crash-worker");
        assertThat(first).isNotNull();
        assertThatThrownBy(() -> worker.processNextChunk(first))
                .isInstanceOf(IllegalStateException.class);
        assertThat(crashes.get()).isEqualTo(1);

        expireLease(datasetId);
        complete(datasetId);

        ReplayDatasetView dataset = service.get(fixture.manager().context(), datasetId);
        assertThat(dataset.lifecycleStatus()).isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
        assertThat(dataset.coverage().processedCount()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM replay_dataset_item_evidence WHERE replay_dataset_id = ?
                """, Integer.class, datasetId)).isOne();
    }

    @Test
    void aStaleWorkerCannotAdvanceADatasetAfterItsLeaseIsTakenOver() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("replay-stale-fence");
        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "replay-stale-fence");

        WorkerClaim stale = worker.claim(datasetId, "stale-worker");
        assertThat(stale).isNotNull();

        // The takeover is applied directly rather than by ageing the lease and re-claiming.
        // Lease expiry is judged against the wall clock, and the schema forbids writing an
        // already-expired lease, so a re-claim here can only be raced into existence — this
        // test was flaky for exactly that reason. The real expiry-and-recovery path is
        // covered by aCrashBetweenChunkEvidenceAndCheckpointResumesWithoutDoubleCounting;
        // what this test owns is the fencing invariant, which is a pure function of the
        // epoch and needs no clock at all.
        takeOverLease(datasetId);
        long currentEpoch = jdbcTemplate.queryForObject("""
                SELECT fencing_epoch FROM replay_dataset_manifests WHERE id = ?
                """, Long.class, datasetId);
        assertThat(currentEpoch).isGreaterThan(stale.fencingEpoch());

        // The stale worker still holds a syntactically valid claim. Only the fencing epoch
        // tells it apart from the live one, which is why it is checked in the same locked
        // statement that reads the row rather than before it.
        assertThat(worker.processNextChunk(stale)).isEqualTo(WorkOutcome.STALE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM replay_dataset_chunks WHERE replay_dataset_id = ?
                """, Integer.class, datasetId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT processed_count FROM replay_dataset_manifests WHERE id = ?
                """, Long.class, datasetId)).isZero();
    }

    /** Simulates another worker winning the lease, without depending on the wall clock. */
    private void takeOverLease(UUID datasetId) {
        jdbcTemplate.update("""
                UPDATE replay_dataset_manifests
                   SET lease_owner = ?, lease_token = ?, fencing_epoch = fencing_epoch + 1
                 WHERE id = ?
                """, "recovering-worker", UUID.randomUUID(), datasetId);
    }

    @Test
    void aCrashBeforeFinalizationLeavesNoPartiallyFinalizedDataset() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("replay-finalize-crash");
        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "replay-finalize-crash");

        AtomicInteger crashes = new AtomicInteger();
        doAnswer(invocation -> {
            if (crashes.getAndIncrement() == 0) {
                throw new IllegalStateException("forced crash before finalization");
            }
            return null;
        }).when(executionProbe).beforeFinalization(any());

        WorkerClaim claim = worker.claim(datasetId, "finalize-crash-worker");
        WorkOutcome outcome = worker.processNextChunk(claim);
        assertThat(outcome).isEqualTo(WorkOutcome.CONTINUE);
        assertThatThrownBy(() -> worker.processNextChunk(claim))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM replay_dataset_manifests WHERE id = ?
                """, String.class, datasetId)).isNotEqualTo("SUCCEEDED");

        expireLease(datasetId);
        complete(datasetId);

        assertThat(service.get(fixture.manager().context(), datasetId).lifecycleStatus())
                .isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
    }

    /**
     * Ages the lease so the next claim is a recovery rather than a fresh claim.
     *
     * <p>{@code ck_replay_dataset_manifests_time} enforces {@code lease_expires_at >
     * updated_at}, so a lease cannot simply be back-dated: the schema does not permit an
     * already-expired lease to be written. Expiry is only reachable by ending the lease
     * immediately after the claim instant, which is itself already in the past.
     */
    private void expireLease(UUID datasetId) {
        // Anchor on created_at rather than the claim instant. created_at precedes a full
        // HTTP creation round trip and a claim transaction, so the expiry it yields is
        // decisively in the past; anchoring on updated_at leaves only the microseconds
        // since the claim and races the wall clock.
        Instant createdAt = jdbcTemplate.queryForObject("""
                SELECT created_at FROM replay_dataset_manifests WHERE id = ?
                """, Instant.class, datasetId);
        jdbcTemplate.update("""
                UPDATE replay_dataset_manifests
                   SET updated_at = ?, lease_expires_at = ?
                 WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(1)),
                datasetId);
    }

    private String datasetRequest(ReplayFixture fixture, Instant labelCutoff,
            UUID simulationId) {
        String simulation = simulationId == null ? "" :
                ",\"compatibilitySimulationRunId\":\"" + simulationId + "\"";
        return "{\"eventCutoffManifestId\":\"" + fixture.cutoffManifestId()
                + "\",\"projectionGenerationId\":\"" + fixture.generationId()
                + "\",\"cohortStart\":\"" + fixture.cohortStart()
                + "\",\"cohortEnd\":\"" + fixture.cohortEnd()
                + "\",\"labelAvailabilityCutoff\":\"" + labelCutoff
                + "\",\"trafficOriginPolicy\":\"LIVE_ONLY\"" + simulation + "}";
    }

    private void complete(UUID datasetId) {
        WorkerClaim claim = worker.claim(datasetId, "offline-replay-test-worker");
        assertThat(claim).isNotNull();
        WorkOutcome outcome;
        int iterations = 0;
        do {
            outcome = worker.processNextChunk(claim);
            iterations++;
        } while (outcome == WorkOutcome.CONTINUE && iterations < 10);
        assertThat(outcome).isEqualTo(WorkOutcome.COMPLETE);
    }

    private ReplayFixture capturedChargebackFixture(String key) throws Exception {
        MerchantAccess merchant = createMerchant("replay-" + key);
        OperatorAccess manager = createOperator(merchant, "RISK_MANAGER", key + "-manager");
        UUID customerId = createCustomer(merchant, key + "@example.test");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, key + "-method");
        UUID paymentIntentId = createCapturedPayment(merchant, customerId, paymentMethodId, key);
        UUID paymentAttemptId = jdbcTemplate.queryForObject("""
                SELECT id FROM payment_attempts WHERE merchant_id = ? AND payment_intent_id = ?
                """, UUID.class, merchant.id(), paymentIntentId);
        UUID decisionId = jdbcTemplate.queryForObject("""
                SELECT id FROM fraud_decisions WHERE merchant_id = ? AND payment_attempt_id = ?
                """, UUID.class, merchant.id(), paymentAttemptId);
        Instant decisionAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM fraud_decisions WHERE id = ?", Instant.class, decisionId);
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String chargeback = "{\"externalEventKey\":\"" + key
                + ".chargeback\",\"paymentIntentId\":\"" + paymentIntentId
                + "\",\"amount\":1200,\"currency\":\"USD\","
                + "\"reasonCode\":\"FRAUDULENT_STOLEN_CREDENTIAL\","
                + "\"evidenceOccurredAt\":\"" + receivedAt.minusSeconds(60)
                + "\",\"receivedAt\":\"" + receivedAt
                + "\",\"evidenceDueAt\":\"" + receivedAt.plus(1, ChronoUnit.DAYS) + "\"}";
        mockMvc.perform(post("/chargebacks").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-chargeback")
                        .contentType(APPLICATION_JSON).content(chargeback))
                .andExpect(status().isCreated());
        Instant labelAvailableAt = jdbcTemplate.queryForObject("""
                SELECT available_at FROM fraud_labels
                 WHERE merchant_id = ? AND payment_attempt_id = ?
                """, Instant.class, merchant.id(), paymentAttemptId);
        Instant cutoffAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (cutoffAt.isBefore(labelAvailableAt)) {
            cutoffAt = labelAvailableAt;
        }
        Cutoff cutoff = createCutoff(merchant.id(), paymentIntentId, cutoffAt);
        Instant cohortStart = decisionAt.minusSeconds(1);
        Instant cohortEnd = labelAvailableAt.minus(1, ChronoUnit.MICROS);
        assertThat(cohortEnd).isAfter(decisionAt);
        return new ReplayFixture(merchant, manager, paymentIntentId, paymentAttemptId,
                decisionId, cohortStart, cohortEnd, labelAvailableAt, cutoff.manifestId(),
                cutoff.generationId(), cutoffAt);
    }

    private Cutoff createCutoff(UUID merchantId, UUID paymentIntentId, Instant createdAt) {
        EventHead head = jdbcTemplate.query("""
                SELECT event.id, event.stream_version, event.event_record_sha256
                  FROM domain_events event
                 WHERE event.merchant_id = ? AND event.stream_type = 'PAYMENT'
                   AND event.stream_id = ?
                 ORDER BY event.stream_version DESC LIMIT 1
                """, resultSet -> resultSet.next() ? new EventHead(
                        resultSet.getObject(1, UUID.class), resultSet.getLong(2),
                        resultSet.getString(3)) : null, merchantId, paymentIntentId);
        assertThat(head).isNotNull();
        Long eventCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events
                 WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, Long.class, merchantId, paymentIntentId);
        String cutoffDigest = SecretHasher.sha256(merchantId + "|PAYMENT|" + paymentIntentId
                + "|" + head.streamVersion() + "|" + head.recordDigest());
        String bootstrapDigest = SecretHasher.sha256("empty-bootstrap-v1");
        UUID cutoffId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_cutoff_manifests
                    (id, projection_definition_id, bootstrap_manifest_id,
                     handler_version, traffic_origin_policy, stream_count,
                     event_count, bootstrap_fact_count, canonical_manifest_sha256,
                     bootstrap_manifest_sha256, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000901', NULL,
                        'v1', 'ALL', 1, ?, 0, ?, ?, ?)
                """, cutoffId, eventCount, cutoffDigest, bootstrapDigest,
                Timestamp.from(createdAt));
        jdbcTemplate.update("""
                INSERT INTO projection_cutoff_stream_positions
                    (id, cutoff_manifest_id, merchant_id, stream_type, stream_id,
                     maximum_stream_version, terminal_domain_event_id,
                     terminal_event_record_sha256)
                VALUES (?, ?, ?, 'PAYMENT', ?, ?, ?, ?)
                """, UUID.randomUUID(), cutoffId, merchantId, paymentIntentId,
                head.streamVersion(), head.eventId(), head.recordDigest());
        UUID generationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_generations
                    (id, projection_definition_id, generation_number, handler_version,
                     build_source, lifecycle_status, source_cutoff_digest, created_at,
                     reconciled_at, activated_at, retired_at)
                VALUES (?, '00000000-0000-0000-0000-000000000901', 2, 'v1',
                        'REBUILD', 'RECONCILED', ?, ?, ?, NULL, NULL)
                """, generationId, cutoffDigest, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return new Cutoff(cutoffId, generationId);
    }

    private UUID insertMatchingSuccessfulSimulation(ReplayFixture fixture) {
        UUID runId = UUID.randomUUID();
        UUID executionToken = UUID.randomUUID();
        Instant createdAt = fixture.eventCutoffAt().plus(1, ChronoUnit.MICROS);
        String digest = "a".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_runs
                    (id, merchant_id, created_by_operator_id, candidate_type,
                     candidate_version_id, candidate_schema_version, candidate_json,
                     candidate_digest, cohort_start, cohort_end, as_of,
                     traffic_origin_policy, label_cutoff, label_resolution_policy,
                     evaluator_version, feature_catalogue_version, rule_catalogue_version,
                     taxonomy_version, query_digest, lifecycle_status, execution_token,
                     projection_version, checkpoint_created_at, checkpoint_attempt_id,
                     processed_count, eligible_count, excluded_count,
                     created_at, queued_at, started_at, completed_at, report_expires_at)
                VALUES (?, ?, ?, 'MERCHANT_RISK_POLICY', ?, 'merchant_risk_policy_v1',
                        '{}', ?, ?, ?, ?, 'LIVE_ONLY', ?, 'CURRENT_AT_CUTOFF',
                        'historical_policy_v1', 'v1', 'rules_v1', 'fraud_taxonomy_v1', ?,
                        'SUCCEEDED', ?, 1, ?, ?, 1, 0, 1, ?, ?, ?, ?, ?)
                """, runId, fixture.merchant().id(), fixture.manager().id(), UUID.randomUUID(),
                digest, Timestamp.from(fixture.cohortStart()), Timestamp.from(fixture.cohortEnd()),
                Timestamp.from(fixture.eventCutoffAt()), Timestamp.from(fixture.labelAvailableAt()),
                digest, executionToken, Timestamp.from(fixture.decisionCreatedAt()),
                fixture.paymentAttemptId(), Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(1, ChronoUnit.DAYS)));
        SimulationEvidence evidence = jdbcTemplate.query("""
                SELECT decision.action,
                       COALESCE(payment_authorization.authorized_amount, 0),
                       COALESCE((SELECT SUM(entry.amount) FROM ledger_entries entry
                                  WHERE entry.merchant_id = attempt.merchant_id
                                    AND entry.source_type = 'PAYMENT_INTENT_CAPTURE'
                                    AND entry.source_id = attempt.payment_intent_id
                                    AND entry.account = 'MERCHANT_PAYABLE'
                                    AND entry.direction = 'CREDIT'
                                    AND entry.created_at <= ?), 0),
                       COALESCE((SELECT SUM(refund.amount) FROM refunds refund
                                  WHERE refund.merchant_id = attempt.merchant_id
                                    AND refund.payment_attempt_id = attempt.id
                                    AND refund.created_at <= ?), 0),
                       attempt.traffic_origin
                  FROM payment_attempts attempt
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
                  LEFT JOIN authorizations payment_authorization
                         ON payment_authorization.merchant_id = attempt.merchant_id
                        AND payment_authorization.payment_attempt_id = attempt.id
                 WHERE attempt.merchant_id = ? AND attempt.id = ?
                """, resultSet -> resultSet.next() ? new SimulationEvidence(
                        resultSet.getString(1), resultSet.getLong(2), resultSet.getLong(3),
                        resultSet.getLong(4), resultSet.getString(5)) : null,
                Timestamp.from(fixture.eventCutoffAt()), Timestamp.from(fixture.eventCutoffAt()),
                fixture.merchant().id(), fixture.paymentAttemptId());
        assertThat(evidence).isNotNull();
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_results
                    (id, merchant_id, simulation_run_id, payment_attempt_id,
                     fraud_decision_id, decision_created_at, traffic_origin,
                     original_action, candidate_action, eligibility_status,
                     exclusion_reason, label_outcome, protected_suppression_attempted,
                     authorization_amount, capture_amount, refund_amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'EXCLUDED',
                        'MISSING_FEATURE', 'FRAUD', FALSE, ?, ?, ?, ?)
                """, UUID.randomUUID(), fixture.merchant().id(), runId,
                fixture.paymentAttemptId(), fixture.decisionId(),
                Timestamp.from(fixture.decisionCreatedAt()), evidence.trafficOrigin(),
                evidence.originalAction(), evidence.authorizationAmount(), evidence.captureAmount(),
                evidence.refundAmount(), Timestamp.from(createdAt));
        return runId;
    }

    private UnlinkedDecision insertUnlinkedDecisionAtSameTimestamp(ReplayFixture fixture) {
        UUID attemptId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_attempts (
                    id, merchant_id, payment_intent_id, payment_method_id,
                    idempotency_key, status, attempt_number, fraud_decision_id,
                    processor_authorization_id, three_ds_status, traffic_origin,
                    created_at, updated_at)
                SELECT ?, merchant_id, payment_intent_id, payment_method_id,
                       ?, status, attempt_number + 1, NULL,
                       NULL, three_ds_status, traffic_origin, created_at, updated_at
                  FROM payment_attempts
                 WHERE merchant_id = ? AND id = ?
                """, attemptId, "same-timestamp-unlinked-" + attemptId,
                fixture.merchant().id(), fixture.paymentAttemptId());
        jdbcTemplate.update("""
                INSERT INTO fraud_decisions (
                    id, merchant_id, payment_attempt_id, risk_score, risk_level,
                    action, model_version, ruleset_version, latency_ms,
                    decision_started_at, decision_completed_at,
                    feature_freshness_status, max_feature_age_ms, reason_codes,
                    traffic_origin, created_at)
                SELECT ?, merchant_id, ?, risk_score, risk_level, action, model_version,
                       ruleset_version, latency_ms, decision_started_at,
                       decision_completed_at, feature_freshness_status,
                       max_feature_age_ms, reason_codes, traffic_origin, created_at
                  FROM fraud_decisions
                 WHERE merchant_id = ? AND id = ?
                """, decisionId, attemptId, fixture.merchant().id(), fixture.decisionId());
        jdbcTemplate.update("""
                UPDATE payment_attempts SET fraud_decision_id = ?
                WHERE merchant_id = ? AND id = ?
                """, decisionId, fixture.merchant().id(), attemptId);
        jdbcTemplate.update("""
                INSERT INTO feature_snapshots (
                    id, merchant_id, fraud_decision_id, payment_attempt_id,
                    feature_version, features_json, computed_at,
                    oldest_feature_timestamp, max_feature_age_ms, freshness_status,
                    stale_feature_names, missing_feature_names, traffic_origin, created_at)
                SELECT ?, merchant_id, ?, ?, feature_version, features_json, computed_at,
                       oldest_feature_timestamp, max_feature_age_ms, freshness_status,
                       stale_feature_names, missing_feature_names, traffic_origin, created_at
                  FROM feature_snapshots
                 WHERE merchant_id = ? AND fraud_decision_id = ?
                """, snapshotId, decisionId, attemptId, fixture.merchant().id(),
                fixture.decisionId());
        return new UnlinkedDecision(attemptId, decisionId, snapshotId);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String role, String key)
            throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(response.get("id").asText());
        return new OperatorAccess(id, response.get("operatorCode").asText(),
                response.get("credential").asText(), merchant.id(), role);
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String key)
            throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createCapturedPayment(MerchantAccess merchant, UUID customerId,
            UUID paymentMethodId, String key) throws Exception {
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-intent")
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":1200,\"currency\":\"USD\","
                                + "\"captureMethod\":\"AUTOMATIC\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID intentId = UUID.fromString(intent.get("id").asText());
        mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated());
        return intentId;
    }

    private record MerchantAccess(UUID id, String apiKey) { }

    private record OperatorAccess(UUID id, String code, String secret, UUID merchantId,
            String role) {
        com.thaap.payments.risk.RiskOperatorContext context() {
            return new com.thaap.payments.risk.RiskOperatorContext(merchantId, id, code,
                    com.thaap.payments.risk.RiskOperatorRole.valueOf(role), "risk_test");
        }
    }

    private record EventHead(UUID eventId, long streamVersion, String recordDigest) { }

    private record Cutoff(UUID manifestId, UUID generationId) { }

    private record UnlinkedDecision(UUID attemptId, UUID decisionId, UUID snapshotId) { }

    private record SimulationEvidence(String originalAction, long authorizationAmount,
            long captureAmount, long refundAmount, String trafficOrigin) { }

    private record ReplayFixture(MerchantAccess merchant, OperatorAccess manager,
            UUID paymentIntentId, UUID paymentAttemptId, UUID decisionId,
            Instant cohortStart, Instant cohortEnd, Instant labelAvailableAt,
            UUID cutoffManifestId, UUID generationId, Instant eventCutoffAt) {
        Instant decisionCreatedAt() {
            return cohortStart.plusSeconds(1);
        }
    }
}
