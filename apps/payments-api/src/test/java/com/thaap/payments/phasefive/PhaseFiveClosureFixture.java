package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.event.DomainEvent;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.projection.ProjectionProcessingOutcome;
import com.thaap.payments.projection.TransportedDomainEvent;
import com.thaap.payments.projection.rebuild.ProjectionRebuildControlService;
import com.thaap.payments.projection.rebuild.ProjectionRebuildDispatcher;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.support.DatabaseCleaner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared two-tenant fixture for the integrated Phase 5 closure rows ({@code TD-0008}).
 *
 * <p>Every {@code C52} row asks the same structural question from a different angle: with two
 * merchants running the whole Phase 5 workflow against one database, does the property still
 * hold? The audit's finding was that the integrated closure evidence was an aggregate count
 * rather than per-concern assertions, and a shared fixture is what makes six honest per-concern
 * suites affordable instead of one summary that hides them.
 *
 * <p>Two tenants is the minimum that can fail. A single-tenant fixture cannot distinguish "the
 * query is scoped" from "there was only ever one merchant's data to return", which is the exact
 * class of error every isolation row exists to catch.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.replay-datasets.creation-enabled=true",
        "thaap.replay-datasets.worker-enabled=false",
        "thaap.replay-datasets.chunk-size=1"
})
@Testcontainers(disabledWithoutDocker = true)
abstract class PhaseFiveClosureFixture {

    protected static final String API_KEY = "X-Api-Key";
    protected static final String RISK_KEY = "X-Risk-Operator-Key";
    protected static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    protected static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";
    protected static final String PLATFORM_BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";

    /**
     * Source-of-truth families the closure rows treat as authoritative. Membership is asserted
     * in the non-interference suite so a family cannot be dropped from the comparison silently.
     */
    protected static final List<String> SOURCE_TABLES = List.of(
            "payment_intents", "payment_attempts", "authorizations", "authorization_voids",
            "refunds", "chargebacks", "fraud_decisions", "feature_snapshots", "fraud_labels",
            "rule_execution_results", "manual_review_cases", "review_resolutions",
            "webhook_endpoints", "webhook_events", "webhook_deliveries", "ledger_entries",
            "domain_events");

    /**
     * Started once for every suite in this package rather than per class. A {@code @Container}
     * field on a shared base is stopped when the first subclass finishes, leaving the rest of the
     * closure suites talking to a dead port — which is how this fixture first failed.
     */
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired protected WebApplicationContext webApplicationContext;
    @Autowired protected RequestIdFilter requestIdFilter;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected DatabaseCleaner databaseCleaner;
    @Autowired protected PlatformOperatorService platformOperatorService;
    @Autowired protected ProjectionRebuildControlService rebuildControlService;
    @Autowired protected ProjectionRebuildDispatcher rebuildDispatcher;
    @Autowired protected DomainEventRepository domainEventRepository;
    @Autowired protected PaymentRiskProjectionConsumerService projectionConsumerService;

    protected MockMvc mockMvc;
    protected Tenant first;
    protected Tenant second;
    protected String dataOperatorKey;
    protected String platformAdministratorKey;

    @BeforeEach
    void createTwoTenants() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        first = createTenant("phase-five-first");
        second = createTenant("phase-five-second");
        PlatformOperatorService.MutationResult administrator = platformOperatorService.bootstrap(
                PLATFORM_BOOTSTRAP_TOKEN, "phase-five-admin", "Phase five administrator",
                "phase-five-admin-request");
        platformAdministratorKey = administrator.credential();
        dataOperatorKey = platformOperatorService.create(platformAdministratorKey,
                "phase-five-data-operator", "Phase five data operator",
                PlatformOperatorRole.DATA_OPERATOR, "phase-five-data-request").credential();
        applyEveryEventToTheLiveProjection();
    }

    /**
     * Feeds every committed event through the live projection consumer, in stream order.
     *
     * <p>The consumer is disabled in the test profile, so without this the active generation is
     * empty and a shadow rebuild reconciles against nothing — reconciliation would fail for a
     * reason that has nothing to do with the property under test. Driving the real consumer keeps
     * the live and shadow sides genuinely comparable, which is what makes a reconciliation pass
     * mean something.
     */
    protected void applyEveryEventToTheLiveProjection() {
        List<UUID> ordered = jdbcTemplate.queryForList("""
                SELECT id FROM domain_events
                ORDER BY merchant_id, stream_type, stream_id, stream_version
                """, UUID.class);
        long offset = 0;
        for (UUID eventId : ordered) {
            DomainEvent event = domainEventRepository.findById(eventId).orElseThrow();
            ProjectionProcessingOutcome outcome = projectionConsumerService.process(
                    new TransportedDomainEvent(
                            PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, offset++,
                            TenantQualifiedStreamKey.encode(event.getMerchantId(),
                                    event.getStreamType(), event.getStreamId()),
                            event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                            event.getEventRecordSha256()));
            assertThat(outcome)
                    .describedAs("the live projection refused event %s", eventId)
                    .isEqualTo(ProjectionProcessingOutcome.APPLIED);
        }
        assertThat(ordered).isNotEmpty();
    }

    /**
     * A merchant carrying the whole workflow: a captured payment with a fraud decision, a
     * chargeback-derived fraud label, a webhook endpoint with delivered events, and the ledger
     * entries the capture produced.
     */
    protected Tenant createTenant(String key) throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + key + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID merchantId = UUID.fromString(merchant.get("id").asText());
        String apiKey = merchant.get("apiKey").asText();

        JsonNode manager = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, key + "-manager")
                        .contentType(APPLICATION_JSON).content("{\"role\":\"RISK_MANAGER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode viewer = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, key + "-viewer")
                        .contentType(APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/webhook_endpoints").header(API_KEY, apiKey)
                        .header(IDEMPOTENCY_KEY, key + "-endpoint")
                        .contentType(APPLICATION_JSON)
                        .content("{\"url\":\"https://" + key + ".example/webhooks\"}"))
                .andExpect(status().isCreated());

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

        UUID paymentAttemptId = jdbcTemplate.queryForObject("""
                SELECT id FROM payment_attempts WHERE merchant_id = ? AND payment_intent_id = ?
                """, UUID.class, merchantId, paymentIntentId);
        return new Tenant(merchantId, apiKey, key,
                new Operator(UUID.fromString(manager.get("id").asText()),
                        manager.get("operatorCode").asText(),
                        manager.get("credential").asText(), merchantId,
                        RiskOperatorRole.RISK_MANAGER),
                new Operator(UUID.fromString(viewer.get("id").asText()),
                        viewer.get("operatorCode").asText(),
                        viewer.get("credential").asText(), merchantId,
                        RiskOperatorRole.VIEWER),
                paymentIntentId, paymentAttemptId);
    }

    /**
     * A full-content digest per source family, scoped to one merchant where the table carries a
     * merchant column. Row counts would pass unchanged through an in-place rewrite, which is the
     * failure these rows describe.
     */
    protected Map<String, String> sourceTruth(UUID merchantId) {
        Map<String, String> truth = new LinkedHashMap<>();
        for (String table : SOURCE_TABLES) {
            String scope = hasMerchantColumn(table) ? " WHERE merchant_id = '" + merchantId + "'"
                    : "";
            truth.put(table, jdbcTemplate.queryForObject("""
                    SELECT COALESCE(md5(string_agg(row_to_json(closure_row)::text, '|'
                        ORDER BY row_to_json(closure_row)::text)), 'empty')
                    FROM %s closure_row%s
                    """.formatted(table, scope), String.class));
        }
        return truth;
    }

    private boolean hasMerchantColumn(String table) {
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = 'merchant_id'
                """, Integer.class, table);
        return columns != null && columns > 0;
    }

    protected long ledgerBalance(UUID merchantId) {
        Long balance = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE merchant_id = ?
                """, Long.class, merchantId);
        return balance == null ? Long.MIN_VALUE : balance;
    }

    protected long count(String table, UUID merchantId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE merchant_id = ?", Long.class,
                merchantId);
        return value == null ? 0 : value;
    }

    /** Runs a shadow rebuild through build, reconcile and activation. */
    protected ProjectionRebuildControlService.RebuildView runRebuildToActivation(String key) {
        ProjectionRebuildControlService.RebuildView started = rebuildControlService.start(
                dataOperatorKey, "start-" + key,
                com.thaap.payments.projection.ProjectionNames.PAYMENT_RISK, "ALL",
                "start-" + key + "-request");
        rebuildDispatcher.launchSynchronously(started.rebuildId());
        assertThat(rebuildControlService.reconcile(dataOperatorKey, "reconcile-" + key,
                com.thaap.payments.projection.ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                "reconcile-" + key + "-request").passed()).isTrue();
        assertThat(rebuildControlService.activate(dataOperatorKey, "activate-" + key,
                com.thaap.payments.projection.ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                "activate-" + key + "-request").activated()).isTrue();
        return started;
    }

    protected record Operator(UUID id, String code, String secret, UUID merchantId,
            RiskOperatorRole role) {
        RiskOperatorContext context() {
            return new RiskOperatorContext(merchantId, id, code, role, "risk_test");
        }
    }

    protected record Tenant(UUID merchantId, String apiKey, String key, Operator manager,
            Operator viewer, UUID paymentIntentId, UUID paymentAttemptId) {
    }
}
