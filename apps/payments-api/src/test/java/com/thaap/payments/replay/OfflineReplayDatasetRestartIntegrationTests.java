package com.thaap.payments.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.ReplayDatasetFailureCode;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkOutcome;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C51-OPS-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p><strong>What this suite does not do.</strong> It does not stop and start a JVM. The row asks
 * for restart coverage, and what is proven instead is the state process loss leaves behind and the
 * mechanism that recovers it: a dataset stranded {@code RUNNING} with an expired lease, found by
 * the durable backlog scan that {@link OfflineReplayDatasetDispatcher#recoverBacklog} runs on a
 * timer, and resumed from its checkpoint without recounting committed work. No process-restart
 * harness exists in this repository; building one is a larger decision than this row, and the
 * row's status reflects the narrower claim rather than dressing it up.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.replay-datasets.creation-enabled=true",
        "thaap.replay-datasets.worker-enabled=false",
        "thaap.replay-datasets.chunk-size=1"
})
@Testcontainers(disabledWithoutDocker = true)
class OfflineReplayDatasetRestartIntegrationTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private OfflineReplayDatasetWorker worker;
    @Autowired private OfflineReplayDatasetService service;
    @Autowired private OfflineReplayDatasetMetrics metrics;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier("offlineReplayDatasetMaterializationJob") private Job materializationJob;
    @Autowired @Qualifier("offlineReplayDatasetExecutor") private Executor datasetExecutor;

    private MockMvc mockMvc;
    private final java.util.concurrent.atomic.AtomicInteger generationNumbers =
            new java.util.concurrent.atomic.AtomicInteger(2);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        generationNumbers.set(2);
    }

    /**
     * The row's failure sequence, first clause: restart must not lose the manifest and a queued
     * run must relaunch. A dataset abandoned mid-run is invisible to the scan while its lease is
     * live and claimable the moment it is not.
     */
    @Test
    void aDatasetStrandedByProcessLossIsFoundByTheBacklogScanAndResumes() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("restart-stranded");
        UUID datasetId = createDataset(fixture, "restart-stranded");

        WorkerClaim lost = worker.claim(datasetId, "lost-process-worker");
        assertThat(lost).isNotNull();
        assertThat(worker.processNextChunk(lost)).isEqualTo(WorkOutcome.CONTINUE);
        long processedBeforeLoss = processedCount(datasetId);
        assertThat(processedBeforeLoss).isPositive();

        // The owning process dies here. Its lease is still live, so the backlog scan must leave
        // the dataset alone: reclaiming a lease that has not expired is how two workers end up
        // writing the same chunk.
        assertThat(worker.recoverableDatasetIds(10)).doesNotContain(datasetId);

        expireLease(datasetId);
        assertThat(worker.recoverableDatasetIds(10)).contains(datasetId);

        complete(datasetId);
        assertThat(service.get(fixture.manager().context(), datasetId).lifecycleStatus())
                .isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
        // Resumption picked up after the committed checkpoint rather than replaying it: one
        // evidence row per source item, not one per attempt.
        assertThat(evidenceCount(datasetId)).isEqualTo(processedCount(datasetId));
        assertThat(evidenceCount(datasetId)).isEqualTo(distinctEvidenceItems(datasetId));
    }

    /**
     * Terminal work must leave the scan. A backlog scan that keeps returning finished or failed
     * datasets would have a recovering deployment relaunch them on every poll.
     */
    @Test
    void terminalDatasetsAreNotReturnedByTheBacklogScan() throws Exception {
        ReplayFixture succeeded = capturedChargebackFixture("restart-terminal-success");
        UUID succeededId = createDataset(succeeded, "restart-terminal-success");
        complete(succeededId);

        ReplayFixture failed = capturedChargebackFixture("restart-terminal-failure");
        UUID failedId = createDataset(failed, "restart-terminal-failure");
        WorkerClaim claim = worker.claim(failedId, "terminal-failure-worker");
        assertThat(claim).isNotNull();
        worker.fail(claim, ReplayDatasetFailureCode.DATASET_PROCESSING_FAILED);

        assertThat(lifecycleStatus(succeededId)).isEqualTo("SUCCEEDED");
        assertThat(lifecycleStatus(failedId)).isEqualTo("FAILED");
        assertThat(worker.recoverableDatasetIds(10))
                .doesNotContain(succeededId).doesNotContain(failedId);

        // Even after ageing, terminal work stays terminal — expiry is a lease property, not a
        // lifecycle one.
        expireLeaseIfPresent(succeededId);
        expireLeaseIfPresent(failedId);
        assertThat(worker.recoverableDatasetIds(10))
                .doesNotContain(succeededId).doesNotContain(failedId);
    }

    /**
     * Disabling the worker is a deployment action. Queued work must survive it untouched, and
     * readiness must distinguish "deliberately disabled" from "stuck" — here by refusing to
     * report healthy while creation is still accepting work the worker will never pick up.
     */
    @Test
    void aQueuedDatasetSurvivesWorkerDisableAndReadinessRefusesTheHalfEnabledDeployment()
            throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("restart-worker-disabled");
        UUID datasetId = createDataset(fixture, "restart-worker-disabled");

        OfflineReplayDatasetDispatcher disabled = new OfflineReplayDatasetDispatcher(
                jobLauncher, materializationJob, datasetExecutor, worker, metrics, false, 10);
        disabled.dispatch(datasetId);
        disabled.recoverBacklog();

        assertThat(lifecycleStatus(datasetId)).isEqualTo("QUEUED");
        assertThat(processedCount(datasetId)).isZero();
        assertThat(evidenceCount(datasetId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_owner FROM replay_dataset_manifests WHERE id = ?
                """, String.class, datasetId)).isNull();

        // Creation enabled with the worker off is the configuration rollback that silently
        // accumulates work, so it is reported as DOWN rather than merely out of service.
        assertThat(new OfflineReplayDatasetHealthIndicator(
                jdbcTemplate, clock, false, true, Duration.ofMinutes(5)).health())
                .satisfies(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsEntry("cause", "CREATION_ENABLED_WITHOUT_WORKER");
                });

        // Re-enabling is the whole recovery; the manifest is still there and still queued.
        complete(datasetId);
        assertThat(lifecycleStatus(datasetId)).isEqualTo("SUCCEEDED");
    }

    /**
     * The row asks for source-digest drift to be visible. It cannot be manufactured, and that is
     * the stronger result.
     *
     * <p>{@code assertFrozenSource} compares the digests and versions frozen on the manifest
     * against the live cutoff manifest and generation. Every one of those inputs is immutable by
     * trigger: {@code trg_replay_dataset_manifests_protected} freezes the manifest's
     * {@code cutoff_manifest_id}, {@code projection_generation_id},
     * {@code bootstrap_manifest_sha256}, evaluator and catalogue versions;
     * {@code protect_projection_generation} freezes a generation's {@code source_cutoff_digest};
     * and the cutoff manifest itself cannot be rewritten. So the drift check guards against a
     * corruption the schema already prevents, and this test asserts the prevention rather than
     * simulating the corruption.
     *
     * <p>What is therefore <em>not</em> proven here is the worker's behavior on drift it detects,
     * because no reachable state produces it. The count-drift branch in {@code finalizeDataset},
     * which fires when the cohort's source count moves under a running dataset, is a separate
     * path and is not exercised.
     */
    @Test
    void theFrozenSourceADatasetCompareAgainstCannotBeRewritten() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("restart-source-drift");
        UUID datasetId = createDataset(fixture, "restart-source-drift");

        WorkerClaim claim = worker.claim(datasetId, "drift-worker");
        assertThat(claim).isNotNull();
        assertThat(worker.processNextChunk(claim)).isEqualTo(WorkOutcome.CONTINUE);
        long evidenceBeforeAttempt = evidenceCount(datasetId);
        assertThat(evidenceBeforeAttempt).isPositive();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE projection_cutoff_manifests SET canonical_manifest_sha256 = ?
                WHERE id = ?
                """, SecretHasher.sha256("drifted-cutoff"), fixture.cutoffManifestId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE projection_generations SET source_cutoff_digest = ? WHERE id = ?
                """, SecretHasher.sha256("drifted-generation"), fixture.generationId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE replay_dataset_manifests SET evaluator_version = 'tampered' WHERE id = ?
                """, datasetId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE replay_dataset_manifests SET cutoff_manifest_id = ? WHERE id = ?
                """, UUID.randomUUID(), datasetId)).isInstanceOf(DataAccessException.class);

        // The run is untouched by the attempts and still completes against its frozen source.
        assertThat(evidenceCount(datasetId)).isEqualTo(evidenceBeforeAttempt);
        WorkOutcome outcome;
        int iterations = 0;
        do {
            outcome = worker.processNextChunk(claim);
            iterations++;
        } while (outcome == WorkOutcome.CONTINUE && iterations < 10);
        assertThat(outcome).isEqualTo(WorkOutcome.COMPLETE);
        assertThat(lifecycleStatus(datasetId)).isEqualTo("SUCCEEDED");
    }

    /**
     * The row's "cleanup attempt" partition: retention tidying must not be able to remove the
     * evidence an equivalence comparison depends on. The guarantee is a database trigger, not a
     * convention in application code, so it is asserted against the database.
     */
    @Test
    void immutableDatasetEvidenceCannotBeDeletedOrRewritten() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("restart-cleanup");
        UUID datasetId = createDataset(fixture, "restart-cleanup");
        complete(datasetId);
        assertThat(evidenceCount(datasetId)).isPositive();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM replay_dataset_item_evidence WHERE replay_dataset_id = ?", datasetId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM replay_dataset_results WHERE replay_dataset_id = ?", datasetId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM replay_dataset_chunks WHERE replay_dataset_id = ?", datasetId))
                .isInstanceOf(DataAccessException.class);
        assertThat(evidenceCount(datasetId)).isPositive();
        assertThat(lifecycleStatus(datasetId)).isEqualTo("SUCCEEDED");
    }

    /**
     * A realistic retained upgrade must install the replay substrate, carry prior rows forward,
     * and invent no dataset history. A fabricated manifest would be a dataset nothing ever ran.
     */
    @Test
    void aRetainedUpgradePreservesPriorRowsAndFabricatesNoDatasetRuns() {
        String schema = "replay_retained_" + UUID.randomUUID().toString().replace("-", "");
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
                    VALUES (?, 'retained-replay-merchant', 'ACTIVE', 'retained_', ?,
                        CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "a".repeat(64));

            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .load().migrate();

            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".merchants WHERE id = ?",
                    Integer.class, merchantId)).isEqualTo(1);
            for (String table : new String[] {"replay_dataset_manifests",
                    "replay_dataset_item_evidence", "replay_dataset_results",
                    "replay_dataset_chunks", "replay_dataset_compatibility_evidence"}) {
                assertThat(retained.queryForObject(
                        "SELECT COUNT(*) FROM " + schema + "." + table, Integer.class))
                        .describedAs("retained upgrade fabricated rows in %s", table)
                        .isZero();
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void complete(UUID datasetId) {
        WorkerClaim claim = worker.claim(datasetId, "restart-test-worker");
        assertThat(claim).isNotNull();
        WorkOutcome outcome;
        int iterations = 0;
        do {
            outcome = worker.processNextChunk(claim);
            iterations++;
        } while (outcome == WorkOutcome.CONTINUE && iterations < 10);
        assertThat(outcome).isEqualTo(WorkOutcome.COMPLETE);
    }

    /**
     * Ages the lease so the backlog scan treats the dataset as abandoned.
     * {@code ck_replay_dataset_manifests_time} forbids writing an already-expired lease, so
     * expiry is only reachable by anchoring on {@code created_at}, which precedes a full
     * creation round trip and is decisively in the past.
     */
    private void expireLease(UUID datasetId) {
        Instant createdAt = jdbcTemplate.queryForObject("""
                SELECT created_at FROM replay_dataset_manifests WHERE id = ?
                """, Instant.class, datasetId);
        jdbcTemplate.update("""
                UPDATE replay_dataset_manifests SET updated_at = ?, lease_expires_at = ?
                WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(1)),
                datasetId);
    }

    /** Terminal datasets hold no lease, so ageing one is a no-op rather than an error. */
    private void expireLeaseIfPresent(UUID datasetId) {
        Boolean leased = jdbcTemplate.queryForObject("""
                SELECT lease_expires_at IS NOT NULL FROM replay_dataset_manifests WHERE id = ?
                """, Boolean.class, datasetId);
        if (Boolean.TRUE.equals(leased)) {
            expireLease(datasetId);
        }
    }

    private String lifecycleStatus(UUID datasetId) {
        return jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM replay_dataset_manifests WHERE id = ?
                """, String.class, datasetId);
    }

    private long processedCount(UUID datasetId) {
        return jdbcTemplate.queryForObject("""
                SELECT processed_count FROM replay_dataset_manifests WHERE id = ?
                """, Long.class, datasetId);
    }

    private long evidenceCount(UUID datasetId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM replay_dataset_item_evidence WHERE replay_dataset_id = ?
                """, Long.class, datasetId);
    }

    private long distinctEvidenceItems(UUID datasetId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT item_ordinal) FROM replay_dataset_item_evidence
                WHERE replay_dataset_id = ?
                """, Long.class, datasetId);
    }

    private UUID createDataset(ReplayFixture fixture, String key) throws Exception {
        String response = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"eventCutoffManifestId\":\"" + fixture.cutoffManifestId()
                                + "\",\"projectionGenerationId\":\"" + fixture.generationId()
                                + "\",\"cohortStart\":\"" + fixture.cohortStart()
                                + "\",\"cohortEnd\":\"" + fixture.cohortEnd()
                                + "\",\"labelAvailabilityCutoff\":\"" + fixture.labelAvailableAt()
                                + "\",\"trafficOriginPolicy\":\"LIVE_ONLY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("datasetId").asText());
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
        Instant receivedAt = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        mockMvc.perform(post("/chargebacks").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-chargeback")
                        .contentType(APPLICATION_JSON)
                        .content("{\"externalEventKey\":\"" + key
                                + ".chargeback\",\"paymentIntentId\":\"" + paymentIntentId
                                + "\",\"amount\":1200,\"currency\":\"USD\","
                                + "\"reasonCode\":\"FRAUDULENT_STOLEN_CREDENTIAL\","
                                + "\"evidenceOccurredAt\":\"" + receivedAt.minusSeconds(60)
                                + "\",\"receivedAt\":\"" + receivedAt
                                + "\",\"evidenceDueAt\":\""
                                + receivedAt.plus(1, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isCreated());
        Instant labelAvailableAt = jdbcTemplate.queryForObject("""
                SELECT available_at FROM fraud_labels
                 WHERE merchant_id = ? AND payment_attempt_id = ?
                """, Instant.class, merchant.id(), paymentAttemptId);
        Instant cutoffAt = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        if (cutoffAt.isBefore(labelAvailableAt)) {
            cutoffAt = labelAvailableAt;
        }
        Cutoff cutoff = createCutoff(merchant.id(), paymentIntentId, cutoffAt);
        Instant cohortEnd = labelAvailableAt.minus(1, ChronoUnit.MICROS);
        assertThat(cohortEnd).isAfter(decisionAt);
        return new ReplayFixture(merchant, manager, decisionAt.minusSeconds(1), cohortEnd,
                labelAvailableAt, cutoff.manifestId(), cutoff.generationId());
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
        UUID cutoffId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_cutoff_manifests
                    (id, projection_definition_id, bootstrap_manifest_id,
                     handler_version, traffic_origin_policy, stream_count,
                     event_count, bootstrap_fact_count, canonical_manifest_sha256,
                     bootstrap_manifest_sha256, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000901', NULL,
                        'v1', 'ALL', 1, ?, 0, ?, ?, ?)
                """, cutoffId, eventCount, cutoffDigest,
                SecretHasher.sha256("empty-bootstrap-v1"), Timestamp.from(createdAt));
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
                VALUES (?, '00000000-0000-0000-0000-000000000901', ?, 'v1',
                        'REBUILD', 'RECONCILED', ?, ?, ?, NULL, NULL)
                """, generationId, generationNumbers.getAndIncrement(), cutoffDigest,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return new Cutoff(cutoffId, generationId);
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
        return new OperatorAccess(UUID.fromString(response.get("id").asText()),
                response.get("operatorCode").asText(), response.get("credential").asText(),
                merchant.id(), role);
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

    private record ReplayFixture(MerchantAccess merchant, OperatorAccess manager,
            Instant cohortStart, Instant cohortEnd, Instant labelAvailableAt,
            UUID cutoffManifestId, UUID generationId) { }
}
