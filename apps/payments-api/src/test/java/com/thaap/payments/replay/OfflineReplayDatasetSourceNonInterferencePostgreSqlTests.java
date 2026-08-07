package com.thaap.payments.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.replay.OfflineReplayDatasetService.ReplayDatasetView;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.ReplayDatasetFailureCode;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkOutcome;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.webhook.WebhookHttpTransport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C51-PAY-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}). The claim is a
 * negative one — building, resuming, reading or comparing an offline replay dataset changes no
 * payment, fraud, label, control, webhook, audit or ledger truth and calls no processor — and
 * a negative claim is only worth what its before/after comparison covers.
 *
 * <p>So the comparison is a full-content digest per table rather than a row count. A count
 * would pass unchanged if a dataset run rewrote a fraud decision's action, re-signed a webhook
 * or moved a ledger entry between accounts. {@code md5(string_agg(row_to_json(...)))} catches
 * any of those, and is the reason this suite is PostgreSQL-only rather than a contract class
 * with a database subclass.
 *
 * <p>{@code theFixtureActuallyCarriesTheTruthItClaimsToScan} exists because every assertion
 * here would pass equally well against a clean scan of nothing.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.replay-datasets.creation-enabled=true",
        "thaap.replay-datasets.worker-enabled=false",
        "thaap.replay-datasets.chunk-size=1"
})
@Testcontainers(disabledWithoutDocker = true)
class OfflineReplayDatasetSourceNonInterferencePostgreSqlTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String REPLAY_AUDIT_ACTION = "offline_replay_dataset.created";

    /**
     * Every table the row names as source truth. Membership is asserted rather than assumed:
     * a family silently dropped from this list would quietly narrow the proof.
     */
    private static final List<String> SOURCE_TABLES = List.of(
            "payment_intents", "payment_attempts", "authorizations", "authorization_voids",
            "refunds", "chargebacks",
            "fraud_decisions", "feature_snapshots", "fraud_labels", "rule_execution_results",
            "fraud_rules", "fraud_lists",
            "manual_review_cases", "review_resolutions",
            "webhook_endpoints", "webhook_events", "webhook_deliveries",
            "webhook_delivery_attempts",
            "audit_logs", "domain_events", "ledger_entries");

    /** Families the fixture must actually populate for the digest comparison to mean anything. */
    private static final List<String> POPULATED_TABLES = List.of(
            "payment_intents", "payment_attempts", "authorizations", "chargebacks",
            "fraud_decisions", "feature_snapshots", "fraud_labels", "webhook_endpoints",
            "webhook_events", "audit_logs", "domain_events", "ledger_entries");

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
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private OfflineReplayDatasetWorker worker;
    @Autowired private OfflineReplayDatasetService service;
    @MockitoSpyBean private OfflineReplayDatasetExecutionProbe executionProbe;
    @MockitoSpyBean private ProcessorAdapter processorAdapter;
    @MockitoSpyBean private WebhookHttpTransport webhookTransport;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void creatingCompletingAndReadingADatasetChangesNoSourceTruth() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("non-interference-success");
        Map<String, String> before = quiesce();

        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "non-interference-success");
        complete(datasetId);
        ReplayDatasetView dataset = service.get(fixture.manager().context(), datasetId);
        assertThat(dataset.lifecycleStatus()).isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);
        mockMvc.perform(get("/risk/replay-datasets/{id}", datasetId)
                        .header(RISK_KEY, fixture.manager().secret()))
                .andExpect(status().isOk());

        assertNoSourceInterference(before, 1);
    }

    /**
     * The dataset run wrote its own rows. If it had not, the previous test would be comparing
     * an unchanged database to an unchanged database and calling that non-interference.
     */
    @Test
    void theFixtureActuallyCarriesTheTruthItClaimsToScan() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("non-interference-canary");
        Map<String, String> before = quiesce();

        for (String table : POPULATED_TABLES) {
            assertThat(before.get(table))
                    .describedAs("source family %s is empty, so comparing it proves nothing",
                            table)
                    .isNotEqualTo("empty");
        }
        assertThat(before.keySet()).containsExactlyElementsOf(SOURCE_TABLES);
        assertThat(ledgerBalance()).isZero();

        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "non-interference-canary");
        complete(datasetId);

        // Dataset-owned writes did happen, so an unchanged source is a real result and not a
        // run that quietly did nothing.
        assertThat(rowCount("replay_dataset_manifests")).isEqualTo(1);
        assertThat(rowCount("replay_dataset_item_evidence")).isPositive();
        assertNoSourceInterference(before, 1);
    }

    /**
     * A dataset that crashes mid-chunk, is taken over and resumes must still touch nothing.
     * A partial run has more chances to write than a clean one: it applies evidence twice,
     * re-reads the same cohort and re-derives the same labels.
     */
    @Test
    void aRestartedDatasetRunChangesNoSourceTruth() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("non-interference-restart");
        Map<String, String> before = quiesce();

        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "non-interference-restart");

        AtomicInteger crashes = new AtomicInteger();
        doAnswer(invocation -> {
            if (crashes.getAndIncrement() == 0) {
                throw new IllegalStateException("forced crash before checkpoint");
            }
            return null;
        }).when(executionProbe).afterChunkEvidenceBeforeCheckpoint(any(), anyLong());

        WorkerClaim crashed = worker.claim(datasetId, "non-interference-crash-worker");
        assertThat(crashed).isNotNull();
        try {
            worker.processNextChunk(crashed);
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("forced crash before checkpoint");
        }
        assertThat(crashes.get()).isEqualTo(1);

        expireLease(datasetId);
        complete(datasetId);
        assertThat(service.get(fixture.manager().context(), datasetId).lifecycleStatus())
                .isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);

        assertNoSourceInterference(before, 1);
    }

    /**
     * A failed dataset must not roll anything back into the source either. Failure is the
     * partition where a builder that had taken a write path would be most likely to leave
     * half of it behind.
     *
     * <p>There is no operator cancel on replay datasets — the lifecycle offers failure, not
     * cancellation — so the canceled partition the row names is covered here as far as the
     * product surface allows, and no further.
     */
    @Test
    void aFailedDatasetRunChangesNoSourceTruth() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("non-interference-failure");
        Map<String, String> before = quiesce();

        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "non-interference-failure");
        WorkerClaim claim = worker.claim(datasetId, "non-interference-failure-worker");
        assertThat(claim).isNotNull();
        assertThat(worker.processNextChunk(claim)).isEqualTo(WorkOutcome.CONTINUE);
        worker.fail(claim, ReplayDatasetFailureCode.DATASET_PROCESSING_FAILED);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM replay_dataset_manifests WHERE id = ?
                """, String.class, datasetId)).isEqualTo("FAILED");
        assertNoSourceInterference(before, 1);
    }

    /**
     * A duplicate create must resolve to the same dataset without re-touching anything, and an
     * equivalence comparison against a governed simulation must read both sides without
     * writing to either — including when the comparison fails, which is the case where a
     * builder might be tempted to "correct" the simulation it disagrees with.
     */
    @Test
    void duplicateCreationAndEquivalenceComparisonChangeNoSourceTruth() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("non-interference-equivalence");
        UUID matching = insertSimulation(fixture, "matching", "FRAUD");
        // A simulation that disagrees with the replayed truth on the label it recorded. It is
        // inserted disagreeing rather than amended afterwards: risk simulation evidence is
        // append-only in the schema, and tampering with it is exactly what that forbids.
        UUID mismatching = insertSimulation(fixture, "mismatching", "LEGITIMATE");
        Map<String, String> before = quiesce();

        String body = datasetRequest(fixture, fixture.labelAvailableAt(), matching);
        MvcResult first = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, "non-interference-duplicate")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult duplicate = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, "non-interference-duplicate")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(duplicate.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        UUID matchedDataset = UUID.fromString(objectMapper.readTree(
                first.getResponse().getContentAsString()).get("datasetId").asText());
        complete(matchedDataset);
        assertThat(service.get(fixture.manager().context(), matchedDataset)
                .compatibilityStatus()).isEqualTo(ReplayDatasetCompatibilityStatus.PASSED);

        UUID mismatchedDataset = createDataset(fixture, fixture.labelAvailableAt(),
                mismatching, "non-interference-mismatch");
        complete(mismatchedDataset);
        ReplayDatasetView mismatched = service.get(fixture.manager().context(),
                mismatchedDataset);
        assertThat(mismatched.compatibilityStatus())
                .isEqualTo(ReplayDatasetCompatibilityStatus.FAILED);

        // The disagreement is recorded on the dataset, never resolved against the source.
        // Two creates: the duplicate resolves to the first and audits nothing further.
        assertNoSourceInterference(before, 2);
    }

    /**
     * The contradictory-label cohort. Two chargebacks on one payment attempt produce two labels
     * that disagree about the fraud type, which is the shape a dataset builder is most tempted to
     * "fix" — picking a winner and writing it back so the cohort reads cleanly. It must instead
     * read the disagreement and leave it exactly where it found it.
     */
    @Test
    void aContradictoryLabelCohortIsReadWithoutResolvingItInTheSource() throws Exception {
        ReplayFixture fixture = capturedChargebackFixture("non-interference-contradictory");
        UUID paymentIntentId = jdbcTemplate.queryForObject("""
                SELECT id FROM payment_intents WHERE merchant_id = ?
                """, UUID.class, fixture.merchant().id());
        // The contradiction that actually occurs: the decision-time judgment and the post-hoc
        // label disagree about the same attempt. This is the ordinary shape of a fraud label —
        // an approval that later turns out to be fraud — and it is exactly the disagreement a
        // dataset builder must carry rather than reconcile.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT decision.action FROM fraud_decisions decision
                JOIN payment_attempts attempt ON attempt.id = decision.payment_attempt_id
                WHERE attempt.payment_intent_id = ?
                """, String.class, paymentIntentId)).isEqualTo("ALLOW");
        assertThat(jdbcTemplate.queryForList("""
                SELECT label_value FROM fraud_labels WHERE merchant_id = ?
                """, String.class, fixture.merchant().id()))
                .describedAs("the fixture does not actually contradict its own decision")
                .containsExactly("FRAUD");

        // The other route to contradiction is closed by the product: a payment carries one
        // chargeback, so a second contradicting chargeback label cannot be introduced at all.
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        mockMvc.perform(post("/chargebacks").header(API_KEY, fixture.merchant().apiKey())
                        .header(IDEMPOTENCY_KEY, "contradictory-second-chargeback")
                        .contentType(APPLICATION_JSON)
                        .content("{\"externalEventKey\":\"contradictory.second\","
                                + "\"paymentIntentId\":\"" + paymentIntentId
                                + "\",\"amount\":1200,\"currency\":\"USD\","
                                + "\"reasonCode\":\"FRAUDULENT_CARD_TESTING\","
                                + "\"evidenceOccurredAt\":\"" + receivedAt.minusSeconds(30)
                                + "\",\"receivedAt\":\"" + receivedAt
                                + "\",\"evidenceDueAt\":\""
                                + receivedAt.plus(1, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = ?
                """, Integer.class, fixture.merchant().id())).isEqualTo(1);

        Map<String, String> before = quiesce();
        UUID datasetId = createDataset(fixture, fixture.labelAvailableAt(), null,
                "non-interference-contradictory");
        complete(datasetId);
        assertThat(service.get(fixture.manager().context(), datasetId).lifecycleStatus())
                .isEqualTo(ReplayDatasetLifecycleStatus.SUCCEEDED);

        assertNoSourceInterference(before, 1);
    }

    /**
     * Captures source truth and silences the fixture's own processor and webhook activity, so
     * that what is verified afterwards is what the dataset did and nothing else.
     */
    private Map<String, String> quiesce() {
        clearInvocations(processorAdapter, webhookTransport);
        return sourceTruth();
    }

    private void assertNoSourceInterference(Map<String, String> before, int expectedCreates) {
        assertThat(sourceTruth()).isEqualTo(before);
        assertThat(ledgerBalance()).isZero();
        verifyNoInteractions(processorAdapter, webhookTransport);

        // The only audit rows a dataset run may add are its own creates, and it must add
        // one per create: a silent audit gap is as much a defect as an unauthorized write.
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT action FROM audit_logs
                WHERE aggregate_type = 'OfflineReplayDataset'
                """, String.class)).containsExactly(REPLAY_AUDIT_ACTION);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action = ?
                """, Integer.class, REPLAY_AUDIT_ACTION)).isEqualTo(expectedCreates);
    }

    /**
     * A full-content digest per source family. Row counts would miss an in-place rewrite,
     * which is the failure this row is actually about: a builder that reuses live decision
     * orchestration does not add rows, it changes them.
     */
    private Map<String, String> sourceTruth() {
        Map<String, String> truth = new LinkedHashMap<>();
        for (String table : SOURCE_TABLES) {
            // The alias must not collide with any column name in any scanned table, or
            // row_to_json resolves to that column and digests one field instead of the row.
            // fraud_labels has a "source" column, which is how this was found.
            truth.put(table, jdbcTemplate.queryForObject("""
                    SELECT COALESCE(md5(string_agg(row_to_json(source_family_row)::text, '|'
                        ORDER BY row_to_json(source_family_row)::text)), 'empty')
                    FROM %s source_family_row %s
                    """.formatted(table, auditAppendFilter(table)), String.class));
        }
        return truth;
    }

    /**
     * Audit is the one family a dataset run is supposed to add to: creating a dataset is an
     * operator action and recording it is the point of the audit log. So audit truth is
     * compared modulo its own append — every pre-existing row must be byte-identical, and the
     * only rows a run may add are its own create records, which
     * {@link #assertNoSourceInterference} checks separately. Comparing audit for exact
     * equality would have failed for the right behavior; not comparing it at all would have
     * let a dataset run rewrite history unnoticed.
     */
    private String auditAppendFilter(String table) {
        return "audit_logs".equals(table)
                ? "WHERE action <> '" + REPLAY_AUDIT_ACTION + "'" : "";
    }

    private long ledgerBalance() {
        Long balance = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount
                                         ELSE -amount END), 0)
                FROM ledger_entries
                """, Long.class);
        return balance == null ? Long.MIN_VALUE : balance;
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private UUID createDataset(ReplayFixture fixture, Instant labelCutoff, UUID simulationId,
            String key) throws Exception {
        String response = mockMvc.perform(post("/risk/replay-datasets")
                        .header(RISK_KEY, fixture.manager().secret())
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content(datasetRequest(fixture, labelCutoff, simulationId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("datasetId").asText());
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
        WorkerClaim claim = worker.claim(datasetId, "non-interference-test-worker");
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
     * Ages the lease so the next claim is a recovery. {@code ck_replay_dataset_manifests_time}
     * forbids writing an already-expired lease, so expiry is only reachable by anchoring on
     * {@code created_at}, which precedes a full creation round trip and is decisively past.
     */
    private void expireLease(UUID datasetId) {
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

    private ReplayFixture capturedChargebackFixture(String key) throws Exception {
        MerchantAccess merchant = createMerchant("replay-" + key);
        OperatorAccess manager = createOperator(merchant, "RISK_MANAGER", key + "-manager");
        UUID customerId = createCustomer(merchant, key + "@example.test");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, key + "-method");
        // The webhook endpoint exists so webhook truth is a populated family rather than an
        // empty one the comparison would pass over.
        createWebhookEndpoint(merchant, key);
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

    private UUID insertSimulation(ReplayFixture fixture, String label,
            String labelOutcome) {
        UUID runId = UUID.randomUUID();
        UUID executionToken = UUID.randomUUID();
        Instant createdAt = fixture.eventCutoffAt().plus(1, ChronoUnit.MICROS);
        String digest = SecretHasher.sha256(label + "-candidate");
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
                digest, Timestamp.from(fixture.cohortStart()),
                Timestamp.from(fixture.cohortEnd()), Timestamp.from(fixture.eventCutoffAt()),
                Timestamp.from(fixture.labelAvailableAt()), digest, executionToken,
                Timestamp.from(fixture.decisionCreatedAt()), fixture.paymentAttemptId(),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt.plus(1, ChronoUnit.DAYS)));
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
                Timestamp.from(fixture.eventCutoffAt()),
                Timestamp.from(fixture.eventCutoffAt()),
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
                        'MISSING_FEATURE', ?, FALSE, ?, ?, ?, ?)
                """, UUID.randomUUID(), fixture.merchant().id(), runId,
                fixture.paymentAttemptId(), fixture.decisionId(),
                Timestamp.from(fixture.decisionCreatedAt()), evidence.trafficOrigin(),
                evidence.originalAction(), labelOutcome, evidence.authorizationAmount(),
                evidence.captureAmount(), evidence.refundAmount(), Timestamp.from(createdAt));
        return runId;
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

    private void createWebhookEndpoint(MerchantAccess merchant, String key) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-webhook-endpoint")
                        .contentType(APPLICATION_JSON)
                        .content("{\"url\":\"https://non-interference.example/webhooks\"}"))
                .andExpect(status().isCreated());
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
