package com.thaap.payments.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkOutcome;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.ApplicationRestartHarness;
import com.thaap.payments.support.DatabaseCleaner;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C51-OPS-001} — the process-restart half.
 *
 * <p>A real application instance is built, a dataset is driven until its worker is inside
 * finalization, and the instance is destroyed with the worker still in there. Nothing about the
 * aftermath is staged: the lease, the committed chunk evidence and the unfinalized manifest are
 * whatever the dead instance left. A second instance then recovers it.
 *
 * <p>Finalization is the chosen kill point because it is where a restart is most dangerous. The
 * chunk work is committed but the dataset is not yet {@code SUCCEEDED}, so a resumed instance
 * that re-ran the cohort would double-count evidence, and one that finalized blindly would
 * publish a dataset whose aggregates never matched its items.
 */
@Testcontainers(disabledWithoutDocker = true)
class OfflineReplayDatasetProcessRestartPostgreSqlTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    private static final AtomicBoolean HALT_AT_FINALIZATION = new AtomicBoolean(true);
    private static final CountDownLatch REACHED_FINALIZATION = new CountDownLatch(1);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private ApplicationRestartHarness harness;
    private JdbcTemplate outsideAnyInstance;

    @BeforeEach
    void setUp() {
        HALT_AT_FINALIZATION.set(true);
        harness = new ApplicationRestartHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())
                .withWebLayer()
                .withConfiguration(HaltingProbeConfiguration.class);
        outsideAnyInstance = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void aDatasetSurvivesTheDeathOfItsProcessAndFinalizesInTheNextInstance() throws Exception {
        ConfigurableApplicationContext dying = start();
        dying.getBean(DatabaseCleaner.class).clean();
        Fixture fixture = seedDataset(dying);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            OfflineReplayDatasetWorker datasetWorker =
                    dying.getBean(OfflineReplayDatasetWorker.class);
            WorkerClaim claim = datasetWorker.claim(fixture.datasetId(), "dying-instance-worker");
            assertThat(claim).isNotNull();
            worker.submit(() -> {
                WorkOutcome outcome;
                int iterations = 0;
                do {
                    outcome = datasetWorker.processNextChunk(claim);
                    iterations++;
                } while (outcome == WorkOutcome.CONTINUE && iterations < 10);
                return outcome;
            });
            assertThat(REACHED_FINALIZATION.await(30, TimeUnit.SECONDS))
                    .describedAs("the worker never reached finalization")
                    .isTrue();

            assertThat(evidenceCount(fixture.datasetId())).isPositive();
            assertThat(lifecycleStatus(fixture.datasetId())).isNotEqualTo("SUCCEEDED");

            harness.stop();
        } finally {
            worker.shutdownNow();
        }

        long evidenceLeftBehind = evidenceCount(fixture.datasetId());
        assertThat(lifecycleStatus(fixture.datasetId())).isNotEqualTo("SUCCEEDED");
        assertThat(evidenceLeftBehind).isPositive();

        expireLease(fixture.datasetId());

        HALT_AT_FINALIZATION.set(false);
        ConfigurableApplicationContext surviving = start();
        OfflineReplayDatasetWorker survivingWorker =
                surviving.getBean(OfflineReplayDatasetWorker.class);
        assertThat(survivingWorker.recoverableDatasetIds(10)).contains(fixture.datasetId());

        WorkerClaim recovered = survivingWorker.claim(fixture.datasetId(), "surviving-worker");
        assertThat(recovered).isNotNull();
        WorkOutcome outcome;
        int iterations = 0;
        do {
            outcome = survivingWorker.processNextChunk(recovered);
            iterations++;
        } while (outcome == WorkOutcome.CONTINUE && iterations < 10);

        assertThat(outcome).isEqualTo(WorkOutcome.COMPLETE);
        assertThat(lifecycleStatus(fixture.datasetId())).isEqualTo("SUCCEEDED");
        // The committed evidence is the same rows, not a second pass over the cohort.
        assertThat(evidenceCount(fixture.datasetId())).isEqualTo(evidenceLeftBehind);
        assertThat(evidenceCount(fixture.datasetId()))
                .isEqualTo(distinctEvidenceItems(fixture.datasetId()));
        assertThat(processedCount(fixture.datasetId())).isEqualTo(evidenceLeftBehind);
    }

    private ConfigurableApplicationContext start() {
        return harness.start(
                "thaap.replay-datasets.creation-enabled=true",
                "thaap.replay-datasets.worker-enabled=false",
                "thaap.replay-datasets.chunk-size=1");
    }

    private Fixture seedDataset(ConfigurableApplicationContext context) throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean(RequestIdFilter.class))
                .build();
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        String key = "process-restart";

        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"replay-" + key + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID merchantId = UUID.fromString(merchant.get("id").asText());
        String apiKey = merchant.get("apiKey").asText();

        JsonNode manager = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, key + "-manager")
                        .contentType(APPLICATION_JSON).content("{\"role\":\"RISK_MANAGER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String managerSecret = manager.get("credential").asText();

        UUID customerId = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/customers").header(API_KEY, apiKey)
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"" + key + "@example.test\"}"))
                        .andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString()).get("id").asText());
        UUID paymentMethodId = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/payment_methods").header(API_KEY, apiKey)
                                .header(IDEMPOTENCY_KEY, key + "-method")
                                .contentType(APPLICATION_JSON)
                                .content("{\"customerId\":\"" + customerId
                                        + "\",\"cardNumber\":\"4242424242424242\"}"))
                        .andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString()).get("id").asText());
        UUID paymentIntentId = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/payment_intents").header(API_KEY, apiKey)
                                .header(IDEMPOTENCY_KEY, key + "-intent")
                                .contentType(APPLICATION_JSON)
                                .content("{\"customerId\":\"" + customerId
                                        + "\",\"amount\":1200,\"currency\":\"USD\","
                                        + "\"captureMethod\":\"AUTOMATIC\"}"))
                        .andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString()).get("id").asText());
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, key + "-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated());

        UUID paymentAttemptId = outsideAnyInstance.queryForObject("""
                SELECT id FROM payment_attempts WHERE merchant_id = ? AND payment_intent_id = ?
                """, UUID.class, merchantId, paymentIntentId);
        Instant decisionAt = outsideAnyInstance.queryForObject("""
                SELECT created_at FROM fraud_decisions
                WHERE merchant_id = ? AND payment_attempt_id = ?
                """, Instant.class, merchantId, paymentAttemptId);
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        mockMvc.perform(post("/chargebacks").header(API_KEY, apiKey)
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

        Instant labelAvailableAt = outsideAnyInstance.queryForObject("""
                SELECT available_at FROM fraud_labels
                WHERE merchant_id = ? AND payment_attempt_id = ?
                """, Instant.class, merchantId, paymentAttemptId);
        Instant cutoffAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (cutoffAt.isBefore(labelAvailableAt)) {
            cutoffAt = labelAvailableAt;
        }
        Cutoff cutoff = createCutoff(merchantId, paymentIntentId, cutoffAt);

        String body = "{\"eventCutoffManifestId\":\"" + cutoff.manifestId()
                + "\",\"projectionGenerationId\":\"" + cutoff.generationId()
                + "\",\"cohortStart\":\"" + decisionAt.minusSeconds(1)
                + "\",\"cohortEnd\":\"" + labelAvailableAt.minus(1, ChronoUnit.MICROS)
                + "\",\"labelAvailabilityCutoff\":\"" + labelAvailableAt
                + "\",\"trafficOriginPolicy\":\"LIVE_ONLY\"}";
        UUID datasetId = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/risk/replay-datasets").header(RISK_KEY, managerSecret)
                                .header(IDEMPOTENCY_KEY, key + "-dataset")
                                .contentType(APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated()).andReturn().getResponse()
                        .getContentAsString()).get("datasetId").asText());
        return new Fixture(datasetId);
    }

    private Cutoff createCutoff(UUID merchantId, UUID paymentIntentId, Instant createdAt) {
        EventHead head = outsideAnyInstance.query("""
                SELECT event.id, event.stream_version, event.event_record_sha256
                  FROM domain_events event
                 WHERE event.merchant_id = ? AND event.stream_type = 'PAYMENT'
                   AND event.stream_id = ?
                 ORDER BY event.stream_version DESC LIMIT 1
                """, resultSet -> resultSet.next() ? new EventHead(
                        resultSet.getObject(1, UUID.class), resultSet.getLong(2),
                        resultSet.getString(3)) : null, merchantId, paymentIntentId);
        assertThat(head).isNotNull();
        Long eventCount = outsideAnyInstance.queryForObject("""
                SELECT COUNT(*) FROM domain_events
                 WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, Long.class, merchantId, paymentIntentId);
        String cutoffDigest = SecretHasher.sha256(merchantId + "|PAYMENT|" + paymentIntentId
                + "|" + head.streamVersion() + "|" + head.recordDigest());
        UUID cutoffId = UUID.randomUUID();
        outsideAnyInstance.update("""
                INSERT INTO projection_cutoff_manifests
                    (id, projection_definition_id, bootstrap_manifest_id, handler_version,
                     traffic_origin_policy, stream_count, event_count, bootstrap_fact_count,
                     canonical_manifest_sha256, bootstrap_manifest_sha256, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000901', NULL, 'v1', 'ALL', 1, ?, 0,
                    ?, ?, ?)
                """, cutoffId, eventCount, cutoffDigest,
                SecretHasher.sha256("empty-bootstrap-v1"), Timestamp.from(createdAt));
        outsideAnyInstance.update("""
                INSERT INTO projection_cutoff_stream_positions
                    (id, cutoff_manifest_id, merchant_id, stream_type, stream_id,
                     maximum_stream_version, terminal_domain_event_id,
                     terminal_event_record_sha256)
                VALUES (?, ?, ?, 'PAYMENT', ?, ?, ?, ?)
                """, UUID.randomUUID(), cutoffId, merchantId, paymentIntentId,
                head.streamVersion(), head.eventId(), head.recordDigest());
        UUID generationId = UUID.randomUUID();
        outsideAnyInstance.update("""
                INSERT INTO projection_generations
                    (id, projection_definition_id, generation_number, handler_version,
                     build_source, lifecycle_status, source_cutoff_digest, created_at,
                     reconciled_at, activated_at, retired_at)
                VALUES (?, '00000000-0000-0000-0000-000000000901', 2, 'v1', 'REBUILD',
                    'RECONCILED', ?, ?, ?, NULL, NULL)
                """, generationId, cutoffDigest, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return new Cutoff(cutoffId, generationId);
    }

    private void expireLease(UUID datasetId) {
        Instant createdAt = outsideAnyInstance.queryForObject("""
                SELECT created_at FROM replay_dataset_manifests WHERE id = ?
                """, Instant.class, datasetId);
        outsideAnyInstance.update("""
                UPDATE replay_dataset_manifests SET updated_at = ?, lease_expires_at = ?
                WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(1)),
                datasetId);
    }

    private String lifecycleStatus(UUID datasetId) {
        return outsideAnyInstance.queryForObject("""
                SELECT lifecycle_status FROM replay_dataset_manifests WHERE id = ?
                """, String.class, datasetId);
    }

    private long processedCount(UUID datasetId) {
        return outsideAnyInstance.queryForObject("""
                SELECT processed_count FROM replay_dataset_manifests WHERE id = ?
                """, Long.class, datasetId);
    }

    private long evidenceCount(UUID datasetId) {
        return outsideAnyInstance.queryForObject("""
                SELECT COUNT(*) FROM replay_dataset_item_evidence WHERE replay_dataset_id = ?
                """, Long.class, datasetId);
    }

    private long distinctEvidenceItems(UUID datasetId) {
        return outsideAnyInstance.queryForObject("""
                SELECT COUNT(DISTINCT item_ordinal) FROM replay_dataset_item_evidence
                WHERE replay_dataset_id = ?
                """, Long.class, datasetId);
    }

    /** Parks the worker inside finalization so the instance can be killed with work in flight. */
    @TestConfiguration
    static class HaltingProbeConfiguration {

        @Bean
        @Primary
        OfflineReplayDatasetExecutionProbe haltingProbe() {
            return new OfflineReplayDatasetExecutionProbe() {
                @Override
                public void beforeFinalization(UUID datasetId) {
                    if (!HALT_AT_FINALIZATION.get()) {
                        return;
                    }
                    REACHED_FINALIZATION.countDown();
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    private record Fixture(UUID datasetId) { }

    private record EventHead(UUID eventId, long streamVersion, String recordDigest) { }

    private record Cutoff(UUID manifestId, UUID generationId) { }
}
