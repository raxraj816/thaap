package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class DeveloperScenarioContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RUNNER_TOKEN = "X-Thaap-Scenario-Runner-Token";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_TOKEN = "test-admin-bootstrap-token";
    private static final String TEST_RUNNER_TOKEN = "test-scenario-runner-token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DeveloperScenarioHealthIndicator healthIndicator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void catalogueIsPublicBoundedAndExplicitlySimulatorOnly() throws Exception {
        JsonNode catalogue = objectMapper.readTree(mockMvc.perform(get("/developer/test-cards"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(catalogue.get("catalogueVersion").asText()).isEqualTo("developer_scenarios_v1");
        assertThat(catalogue.get("testCards")).hasSize(9);
        assertThat(catalogue.get("scenarioTypes")).hasSize(3);
        for (JsonNode card : catalogue.get("testCards")) {
            assertThat(card.get("cardNumber").asText()).matches("499999000000000[1-9]");
            assertThat(card.get("usage").asText()).isEqualTo("SIMULATOR_ONLY");
        }
        assertThat(catalogue.toString()).doesNotContain("apiKey", "credential", "secret", "cvv");
    }

    @Test
    void trustedCredentialCreatesDeterministicTenantRunAndOrdinaryMerchantCannotCreateOne() throws Exception {
        MerchantAccess merchant = createMerchant("developer-determinism");

        mockMvc.perform(post("/developer/scenario-runs")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "ordinary-run")
                        .contentType(APPLICATION_JSON)
                        .content("{\"scenarioType\":\"APPROVED_PAYMENT\",\"seed\":\"stable-seed\"}"))
                .andExpect(status().isForbidden());

        JsonNode first = createRun(merchant, "first-run", "APPROVED_PAYMENT", "stable-seed");
        JsonNode deduplicated = createRun(merchant, "second-run", "APPROVED_PAYMENT", "stable-seed");
        JsonNode distinct = createRun(merchant, "third-run", "APPROVED_PAYMENT", "distinct-seed");

        assertThat(deduplicated.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(deduplicated.get("specificationDigest").asText())
                .isEqualTo(first.get("specificationDigest").asText());
        assertThat(distinct.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(first.get("totalSteps").asInt()).isEqualTo(4);
        assertThat(first.get("nextStep").get("operation").asText()).isEqualTo("CREATE_CUSTOMER");
        assertThat(first.get("seedDigest").asText()).hasSize(64);
        assertThat(first.get("seedDigest").asText())
                .isEqualTo("9be4794ec49cbd4afc172d89e0e9f578b7be4d7e9ae4d744a596b1ca9b873813");
        assertThat(first.get("specificationDigest").asText())
                .isEqualTo("96ff7f22e86195087eb2d9fadaf75795920cda5afd5516877431391c4f7923de");
        assertThat(first.toString()).doesNotContain("stable-seed", merchant.apiKey(), TEST_RUNNER_TOKEN);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM developer_scenario_runs",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void publicApiExecutionAdvancesOnlyVerifiedResourcesAndProducesImmutableSanitizedReport() throws Exception {
        MerchantAccess merchant = createMerchant("developer-happy-path");
        MerchantAccess foreign = createMerchant("developer-foreign");
        JsonNode run = createRun(merchant, "happy-run", "APPROVED_PAYMENT", "merchant-quickstart");
        UUID runId = UUID.fromString(run.get("id").asText());
        UUID workerId = UUID.randomUUID();
        run = claim(merchant, runId, workerId, 200);
        int completedStepIndex = -1;
        UUID completedResourceId = null;

        UUID foreignCustomer = createCustomer(foreign, "foreign@example.test", "foreign-customer");
        mockMvc.perform(post("/developer/scenario-runs/{runId}/steps/0/complete", runId)
                        .headers(trustedHeaders(merchant))
                        .header(IDEMPOTENCY_KEY, "foreign-resource-complete")
                        .contentType(APPLICATION_JSON)
                        .content(completeBody(workerId, run.get("leaseEpoch").asLong(), foreignCustomer)))
                .andExpect(status().isConflict());
        assertThat(currentRun(merchant, runId).get("nextStepIndex").asInt()).isZero();

        while (!"SUCCEEDED".equals(run.get("lifecycleStatus").asText())) {
            JsonNode step = run.get("nextStep");
            UUID resourceId = executeInstruction(merchant, step);
            completedStepIndex = step.get("stepIndex").asInt();
            completedResourceId = resourceId;
            run = complete(merchant, runId, step.get("stepIndex").asInt(), workerId,
                    run.get("leaseEpoch").asLong(), resourceId);
        }

        assertThat(run.get("nextStepIndex").asInt()).isEqualTo(4);
        assertThat(run.get("reportDigest").asText()).hasSize(64);
        JsonNode report = objectMapper.readTree(mockMvc.perform(
                        get("/developer/scenario-runs/{runId}/report", runId)
                                .headers(trustedHeaders(merchant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.at("/report/trafficOrigin").asText()).isEqualTo("SYNTHETIC_SCENARIO");
        assertThat(report.at("/report/outcomeBreakdown/APPROVED").asInt()).isOne();
        assertThat(report.at("/report/stepsCompleted").asInt()).isEqualTo(4);
        assertThat(report.toString()).doesNotContain(merchant.apiKey(), TEST_RUNNER_TOKEN,
                "merchant-quickstart", "4999990000000001", "example.test");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_intents
                 WHERE merchant_id = ? AND traffic_origin = 'SYNTHETIC_SCENARIO'
                """, Integer.class, merchant.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = ?",
                Integer.class, merchant.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM developer_scenario_events WHERE merchant_id = ?",
                Integer.class, merchant.id())).isEqualTo(7);

        String storedReport = jdbcTemplate.queryForObject("""
                SELECT report_json FROM developer_scenario_runs
                 WHERE merchant_id = ? AND id = ?
                """, String.class, merchant.id(), runId);
        assertThat(SecretHasher.sha256(storedReport)).isEqualTo(report.get("reportDigest").asText());
        assertThat(objectMapper.readTree(storedReport)).isEqualTo(report.get("report"));
        assertThat(complete(merchant, runId, completedStepIndex, workerId,
                run.get("leaseEpoch").asLong(), completedResourceId).get("reportDigest").asText())
                .isEqualTo(report.get("reportDigest").asText());
        mockMvc.perform(post("/developer/scenario-runs/{runId}/steps/{stepIndex}/complete",
                        runId, completedStepIndex)
                        .headers(trustedHeaders(merchant))
                        .header(IDEMPOTENCY_KEY, completeKey(runId, completedStepIndex,
                                completedResourceId))
                        .contentType(APPLICATION_JSON)
                        .content(completeBody(workerId, run.get("leaseEpoch").asLong(), UUID.randomUUID())))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/developer/scenario-runs/{runId}", runId)
                        .headers(trustedHeaders(foreign)))
                .andExpect(status().isNotFound());
    }

    @Test
    void terminalReportAndDigestAreBoundOnlyToAttemptsOwnedByTheExactRun() throws Exception {
        MerchantAccess merchant = createMerchant("developer-run-owned-report");
        executeScenarioRun(merchant, "unrelated-before", "unrelated-before-seed");
        JsonNode target = executeScenarioRun(merchant, "target-run", "target-run-seed");
        UUID targetRunId = UUID.fromString(target.get("id").asText());

        JsonNode firstReport = objectMapper.readTree(mockMvc.perform(
                        get("/developer/scenario-runs/{runId}/report", targetRunId)
                                .headers(trustedHeaders(merchant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(firstReport.at("/report/originBreakdown/SYNTHETIC_SCENARIO").asInt()).isOne();
        assertThat(firstReport.at("/report/originBreakdown").size()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_attempts
                 WHERE merchant_id = ? AND traffic_origin = 'SYNTHETIC_SCENARIO'
                """, Integer.class, merchant.id())).isEqualTo(2);

        String stableDigest = firstReport.get("reportDigest").asText();
        JsonNode stableBody = firstReport.get("report");
        executeScenarioRun(merchant, "unrelated-after", "unrelated-after-seed");

        JsonNode replayedReport = objectMapper.readTree(mockMvc.perform(
                        get("/developer/scenario-runs/{runId}/report", targetRunId)
                                .headers(trustedHeaders(merchant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replayedReport.get("reportDigest").asText()).isEqualTo(stableDigest);
        assertThat(replayedReport.get("report")).isEqualTo(stableBody);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_attempts
                 WHERE merchant_id = ? AND traffic_origin = 'SYNTHETIC_SCENARIO'
                """, Integer.class, merchant.id())).isEqualTo(3);
    }

    @Test
    void liveLeaseRejectsConcurrentOwnerAndStepGrammarIsStrict() throws Exception {
        MerchantAccess merchant = createMerchant("developer-lease");
        JsonNode run = createRun(merchant, "lease-run", "ISSUER_DECLINE", "lease-seed");
        UUID runId = UUID.fromString(run.get("id").asText());
        UUID owner = UUID.randomUUID();
        run = claim(merchant, runId, owner, 200);

        claim(merchant, runId, UUID.randomUUID(), 409);
        mockMvc.perform(post("/developer/scenario-runs/{runId}/steps/1/complete", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "wrong-step")
                        .contentType(APPLICATION_JSON)
                        .content(completeBody(owner, run.get("leaseEpoch").asLong(), UUID.randomUUID())))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "malformed-claim")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"%s\",\"unexpected\":true}".formatted(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardTestingPlanExecutesTenOrderedStepsAndReportsEveryGovernedOutcome() throws Exception {
        MerchantAccess merchant = createMerchant("developer-card-testing");
        JsonNode run = createRun(merchant, "card-testing-run", "CARD_TESTING_BURST", "burst-seed");
        UUID runId = UUID.fromString(run.get("id").asText());
        UUID workerId = UUID.randomUUID();
        run = claim(merchant, runId, workerId, 200);

        while (!"SUCCEEDED".equals(run.get("lifecycleStatus").asText())) {
            JsonNode step = run.get("nextStep");
            UUID resourceId = executeInstruction(merchant, step);
            run = complete(merchant, runId, step.get("stepIndex").asInt(), workerId,
                    run.get("leaseEpoch").asLong(), resourceId);
        }

        JsonNode report = objectMapper.readTree(mockMvc.perform(
                        get("/developer/scenario-runs/{runId}/report", runId)
                                .headers(trustedHeaders(merchant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(run.get("totalSteps").asInt()).isEqualTo(10);
        assertThat(run.get("nextStepIndex").asInt()).isEqualTo(10);
        assertThat(report.at("/report/outcomeBreakdown/SUSPECTED_FRAUD").asInt()).isOne();
        assertThat(report.at("/report/outcomeBreakdown/CVC_FAILURE").asInt()).isOne();
        assertThat(report.at("/report/outcomeBreakdown/AVS_LINE1_FAILURE").asInt()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_attempts
                 WHERE merchant_id = ? AND traffic_origin = 'SYNTHETIC_SCENARIO'
                """, Integer.class, merchant.id())).isEqualTo(3);
    }

    @Test
    void failedRunIsTerminalAndOnlyExactFailureReplayIsIdempotent() throws Exception {
        MerchantAccess merchant = createMerchant("developer-failure");
        JsonNode run = createRun(merchant, "failure-run", "ISSUER_DECLINE", "failure-seed");
        UUID runId = UUID.fromString(run.get("id").asText());
        UUID workerId = UUID.randomUUID();
        run = claim(merchant, runId, workerId, 200);
        long leaseEpoch = run.get("leaseEpoch").asLong();

        String failureBody = failBody(workerId, leaseEpoch, "CLIENT_STEP_FAILED");
        String firstFailure = mockMvc.perform(post("/developer/scenario-runs/{runId}/fail", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "terminal-failure")
                        .contentType(APPLICATION_JSON)
                        .content(failureBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String exactReplay = mockMvc.perform(post("/developer/scenario-runs/{runId}/fail", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "terminal-failure")
                        .contentType(APPLICATION_JSON)
                        .content(failureBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode failed = objectMapper.readTree(firstFailure);
        assertThat(objectMapper.readTree(exactReplay)).isEqualTo(failed);
        assertThat(failed.get("lifecycleStatus").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failureCode").asText()).isEqualTo("CLIENT_STEP_FAILED");
        assertThat(failed.get("nextStepIndex").asInt()).isZero();
        assertThat(failed.get("leaseOwner").isNull()).isTrue();

        mockMvc.perform(post("/developer/scenario-runs/{runId}/fail", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "terminal-failure")
                        .contentType(APPLICATION_JSON)
                        .content(failBody(workerId, leaseEpoch, "API_UNAVAILABLE")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/developer/scenario-runs/{runId}/report", runId)
                        .headers(trustedHeaders(merchant)))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM developer_scenario_events
                 WHERE merchant_id = ? AND scenario_run_id = ? AND event_type = 'RUN_FAILED'
                """, Integer.class, merchant.id(), runId)).isOne();
    }

    @Test
    void everyScenarioMutationRequiresTransportIdempotencyAndReplayKeepsRunnerAuthority()
            throws Exception {
        MerchantAccess merchant = createMerchant("developer-idempotency-boundary");
        JsonNode run = createRun(merchant, "idempotency-run", "APPROVED_PAYMENT", "idempotency-seed");
        UUID runId = UUID.fromString(run.get("id").asText());
        UUID workerId = UUID.randomUUID();

        mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .headers(trustedHeaders(merchant)).contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"%s\"}".formatted(workerId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/developer/scenario-runs/{runId}/steps/0/complete", runId)
                        .headers(trustedHeaders(merchant)).contentType(APPLICATION_JSON)
                        .content(completeBody(workerId, 1, UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/developer/scenario-runs/{runId}/fail", runId)
                        .headers(trustedHeaders(merchant)).contentType(APPLICATION_JSON)
                        .content(failBody(workerId, 1, "CLIENT_STEP_FAILED")))
                .andExpect(status().isBadRequest());

        String claimBody = "{\"workerId\":\"%s\"}".formatted(workerId);
        String first = mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "stable-claim")
                        .contentType(APPLICATION_JSON).content(claimBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "stable-claim")
                        .contentType(APPLICATION_JSON).content(claimBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));

        mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, "stable-claim")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "stable-claim")
                        .contentType(APPLICATION_JSON).content(claimBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void readinessAndMetricsExposeOnlyClosedNonIdentifyingDimensions() throws Exception {
        MerchantAccess merchant = createMerchant("developer-observability");
        createRun(merchant, "observability-run", "APPROVED_PAYMENT", "private-seed-canary");

        assertThat(healthIndicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(healthIndicator.health().getDetails())
                .containsEntry("catalogue", "developer_scenarios_v1")
                .containsEntry("testCards", 9);
        for (Meter meter : meterRegistry.find("thaap.developer.scenario.operations").meters()) {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                    .containsExactlyInAnyOrder("operation", "scenario_type", "outcome", "transaction");
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getValue())
                    .allSatisfy(value -> assertThat(value)
                            .doesNotContain("private-seed-canary", merchant.apiKey(), merchant.id().toString()));
        }
    }

    private JsonNode createRun(MerchantAccess merchant, String key, String type, String seed) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/developer/scenario-runs")
                        .headers(trustedHeaders(merchant)).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"scenarioType\":\"%s\",\"seed\":\"%s\"}".formatted(type, seed)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode executeScenarioRun(MerchantAccess merchant, String key, String seed) throws Exception {
        JsonNode run = createRun(merchant, key, "APPROVED_PAYMENT", seed);
        UUID runId = UUID.fromString(run.get("id").asText());
        UUID workerId = UUID.randomUUID();
        run = claim(merchant, runId, workerId, 200);
        while (!"SUCCEEDED".equals(run.get("lifecycleStatus").asText())) {
            JsonNode step = run.get("nextStep");
            UUID resourceId = executeInstruction(merchant, step);
            run = complete(merchant, runId, step.get("stepIndex").asInt(), workerId,
                    run.get("leaseEpoch").asLong(), resourceId);
        }
        return run;
    }

    private JsonNode claim(MerchantAccess merchant, UUID runId, UUID workerId, int statusCode) throws Exception {
        String body = mockMvc.perform(post("/developer/scenario-runs/{runId}/claims", runId)
                        .headers(trustedHeaders(merchant))
                        .header(IDEMPOTENCY_KEY, "claim-" + runId + "-" + workerId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"%s\"}".formatted(workerId)))
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode complete(MerchantAccess merchant, UUID runId, int stepIndex, UUID workerId,
            long leaseEpoch, UUID resourceId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post(
                        "/developer/scenario-runs/{runId}/steps/{stepIndex}/complete", runId, stepIndex)
                        .headers(trustedHeaders(merchant))
                        .header(IDEMPOTENCY_KEY, completeKey(runId, stepIndex, resourceId))
                        .contentType(APPLICATION_JSON)
                        .content(completeBody(workerId, leaseEpoch, resourceId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID executeInstruction(MerchantAccess merchant, JsonNode step) throws Exception {
        String response = mockMvc.perform(post(step.get("path").asText())
                        .headers(trustedHeaders(merchant))
                        .header(IDEMPOTENCY_KEY, step.get("idempotencyKey").asText())
                        .contentType(APPLICATION_JSON).content(step.get("body").toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private JsonNode currentRun(MerchantAccess merchant, UUID runId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/developer/scenario-runs/{runId}", runId)
                        .headers(trustedHeaders(merchant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID createCustomer(MerchantAccess merchant, String email, String key) throws Exception {
        String body = mockMvc.perform(post("/customers").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        String body = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, TEST_ADMIN_TOKEN).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new MerchantAccess(UUID.fromString(json.get("id").asText()), json.get("apiKey").asText());
    }

    private org.springframework.http.HttpHeaders trustedHeaders(MerchantAccess merchant) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add(API_KEY, merchant.apiKey());
        headers.add(RUNNER_TOKEN, TEST_RUNNER_TOKEN);
        return headers;
    }

    private String completeBody(UUID workerId, long leaseEpoch, UUID resourceId) {
        return "{\"workerId\":\"%s\",\"leaseEpoch\":%d,\"resourceId\":\"%s\"}"
                .formatted(workerId, leaseEpoch, resourceId);
    }

    private String completeKey(UUID runId, int stepIndex, UUID resourceId) {
        return "complete-" + runId + "-" + stepIndex + "-" + resourceId;
    }

    private String failBody(UUID workerId, long leaseEpoch, String failureCode) {
        return "{\"workerId\":\"%s\",\"leaseEpoch\":%d,\"failureCode\":\"%s\"}"
                .formatted(workerId, leaseEpoch, failureCode);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
