package com.thaap.payments.bulk;

import static com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER;
import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C55-SEC-001: saved-view and bulk-action rows, responses, errors, and logs carry only
 * safe typed filters and identifiers. The idempotency key in particular must never be readable
 * back out — it is stored as an HMAC identity, and a caller who can read another caller's key
 * can hijack their replay. Canaries are planted in the fixture so a leak has something to leak.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class InvestigationWorkflowPrivacyTests {

    private static final String CARD_NUMBER = "4999990000000003";
    private static final String IDEMPOTENCY_KEY = "canary-idempotency-key-must-not-leak";
    private static final String VIEW_NAME = "canary-view-name";

    private static final Pattern PAN_SHAPED = Pattern.compile("\\b[0-9]{13,19}\\b");
    private static final Pattern DATABASE_TEXT = Pattern.compile(
            "(?i)(select |insert into|update .* set |from review_bulk_action|"
                    + "violates .* constraint|Failing row contains|SQLException|"
                    + "org\\.postgresql|org\\.h2\\.jdbc)");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private ReviewBulkDispatcher dispatcher;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;
    private Payment reviewed;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("workflow-privacy-owner");
        manager = scenario.operator(merchant, "workflow-privacy-manager", "RISK_MANAGER");
        reviewed = scenario.reviewedPayment(merchant, "workflow-privacy");
        scenario.drainProjection();
    }

    @Test
    void noSavedViewOrBulkResponseCarriesCredentialsIdempotencyKeysOrCardData() throws Exception {
        List<String> prohibited = new ArrayList<>(List.of(
                CARD_NUMBER, "workflow-privacy@example.com",
                merchant.apiKey(), manager.credential(), IDEMPOTENCY_KEY));

        for (String body : workflowResponses()) {
            for (String canary : prohibited) {
                assertThat(body).as("a workflow response leaked a prohibited value: %s", canary)
                        .doesNotContain(canary);
            }
            assertThat(PAN_SHAPED.matcher(body).find())
                    .as("a workflow response contains a PAN-shaped digit run").isFalse();
            assertThat(DATABASE_TEXT.matcher(body).find())
                    .as("a workflow response contains database or driver text").isFalse();
        }
    }

    @Test
    void theIdempotencyKeyIsStoredOnlyAsAnHmacIdentityAndNeverInPlaintext() throws Exception {
        startBulkAction(IDEMPOTENCY_KEY);

        String storedIdentity = jdbcTemplate.queryForObject("""
                SELECT idempotency_identity_hmac FROM review_bulk_action_requests
                """, String.class);
        assertThat(storedIdentity)
                .as("the raw idempotency key must never be persisted")
                .isNotEqualTo(IDEMPOTENCY_KEY)
                .hasSize(64)
                .matches("[0-9a-f]{64}");

        // Nothing in any text-bearing column of the bulk tables may contain the raw key. The scan
        // is driven from information_schema rather than a hand-written column list so a future
        // column cannot quietly become an unscanned hiding place.
        for (String table : List.of("review_bulk_action_requests", "review_bulk_action_items")) {
            List<String> textColumns = jdbcTemplate.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE lower(table_name) = ? AND data_type IN
                          ('character varying', 'CHARACTER VARYING', 'text', 'TEXT')
                    """, String.class, table);
            assertThat(textColumns).as("%s has no scannable text column", table).isNotEmpty();
            for (String column : textColumns) {
                List<String> values = jdbcTemplate.queryForList(
                        "SELECT " + column + " FROM " + table, String.class);
                assertThat(values).as("%s.%s stored the raw idempotency key", table, column)
                        .allSatisfy(value -> assertThat(value == null ? "" : value)
                                .doesNotContain(IDEMPOTENCY_KEY));
            }
        }
    }

    @Test
    void savedViewRowsStoreOnlyTypedFilterBytesAndNeverFreeTextOrRawIdentifiers() throws Exception {
        createSavedView();

        String canonicalFilter = jdbcTemplate.queryForObject("""
                SELECT canonical_filter_json FROM payment_investigation_saved_views
                """, String.class);
        // The codec is closed and table-driven, so a stored definition can only contain the
        // frozen key set. Anything else would mean an open grammar reached persistence.
        JsonNode parsed = objectMapper.readTree(canonicalFilter);
        assertThat(parsed.isObject()).isTrue();
        assertThat(parsed.properties()).allSatisfy(entry ->
                assertThat(entry.getKey()).isIn("lifecycleStatuses", "fraudActions", "riskLevels",
                        "reasonCodes", "reviewStatuses", "reviewOutcomes", "labelValues",
                        "fraudTypes", "trafficOrigins", "currencies", "minimumAmountMinor",
                        "maximumAmountMinor", "createdFrom", "createdTo"));

        assertThat(canonicalFilter)
                .doesNotContain(CARD_NUMBER, merchant.apiKey(), manager.credential(),
                        "SELECT", "http://", "https://");
    }

    @Test
    void anInvalidBulkCursorReturnsASafeCodeWithoutEchoingTheHostileInput(CapturedOutput output)
            throws Exception {
        JsonNode started = startBulkAction("cursor-probe-key");

        String body = mockMvc.perform(get("/risk/workspace/review-bulk-actions/{id}/items",
                        started.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .param("cursor", "'; DROP TABLE review_bulk_action_items; --"))
                .andReturn().getResponse().getContentAsString();

        JsonNode error = objectMapper.readTree(body);
        // The signed-cursor codec rejects an unverifiable cursor before the ordinal is ever
        // parsed, so the code is the codec's own. `bulk_cursor_invalid` covers the narrower
        // case of a correctly signed cursor carrying an out-of-range ordinal.
        assertThat(error.get("error").asText()).isEqualTo("workspace_cursor_invalid");
        assertThat(error.get("message").asText())
                .doesNotContain("DROP TABLE", "review_bulk_action_items");
        assertThat(DATABASE_TEXT.matcher(body).find()).isFalse();
        assertThat(output.getAll()).doesNotContain("DROP TABLE review_bulk_action_items");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_bulk_action_items", Integer.class)).isPositive();
    }

    @Test
    void workflowLogsNeverRecordCredentialsIdempotencyKeysOrCardData(CapturedOutput output)
            throws Exception {
        workflowResponses();

        String logs = output.getAll();
        for (String canary : List.of(CARD_NUMBER, "workflow-privacy@example.com",
                merchant.apiKey(), manager.credential(), IDEMPOTENCY_KEY)) {
            assertThat(logs).as("logs leaked a prohibited value: %s", canary)
                    .doesNotContain(canary);
        }
    }

    /** Every saved-view and bulk-assignment response surface, exercised once so each can be scanned. */
    private List<String> workflowResponses() throws Exception {
        List<String> bodies = new ArrayList<>();
        JsonNode view = createSavedView();
        bodies.add(view.toString());

        bodies.add(mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        bodies.add(mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute",
                        view.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode started = startBulkAction(IDEMPOTENCY_KEY);
        bodies.add(started.toString());

        bodies.add(mockMvc.perform(get("/risk/workspace/review-bulk-actions/{id}",
                        started.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        bodies.add(mockMvc.perform(get("/risk/workspace/review-bulk-actions/{id}/items",
                        started.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return bodies;
    }

    @Test
    void springBatchMetadataCarriesOnlyIdentifiersAndNeverACredentialOrIdempotencyKey()
            throws Exception {
        JsonNode started = startBulkAction(IDEMPOTENCY_KEY);
        // The worker is disabled in this context, so the job is launched explicitly. It writes
        // the same framework metadata a live worker would.
        dispatcher.launchSynchronously(UUID.fromString(started.get("id").asText()));

        List<String> batchTables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE UPPER(table_name) LIKE 'BATCH!_%' ESCAPE '!'
                """, String.class);
        assertThat(batchTables)
                .as("the Spring Batch metadata scan is vacuous if no batch table exists")
                .isNotEmpty();

        List<String> prohibited = List.of(IDEMPOTENCY_KEY, manager.credential(),
                merchant.apiKey(), CARD_NUMBER, VIEW_NAME);
        for (String table : batchTables) {
            // Column discovery rather than a hand-written list: a future framework column cannot
            // become an unscanned hiding place for a credential.
            List<String> columns = jdbcTemplate.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = ? AND data_type IN
                      ('character varying', 'varchar', 'text', 'clob', 'character large object')
                    """, String.class, table);
            for (String column : columns) {
                List<String> values = jdbcTemplate.queryForList(
                        "SELECT CAST(" + column + " AS VARCHAR) FROM " + table
                                + " WHERE " + column + " IS NOT NULL", String.class);
                for (String value : values) {
                    for (String canary : prohibited) {
                        assertThat(value)
                                .as("%s.%s leaked a prohibited value into batch metadata",
                                        table, column)
                                .doesNotContain(canary);
                    }
                    assertThat(PAN_SHAPED.matcher(value).find())
                            .as("%s.%s contains a PAN-shaped digit run", table, column)
                            .isFalse();
                    assertThat(DATABASE_TEXT.matcher(value).find())
                            .as("%s.%s contains database or driver text", table, column)
                            .isFalse();
                }
            }
        }
    }

    private JsonNode createSavedView() throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "privacy-view-key")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + VIEW_NAME + "\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":{\"currencies\":[\"USD\"]}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode startBulkAction(String idempotencyKey) throws Exception {
        UUID reviewCaseId = reviewed.reviewCaseId() == null
                ? UUID.randomUUID() : reviewed.reviewCaseId();
        return objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"ASSIGN_TO_SELF\",\"items\":[{\"reviewCaseId\":\""
                                + reviewCaseId + "\",\"expectedAssignmentVersion\":0}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
    }
}
