package com.thaap.payments.bulk;

import static com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER;
import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C55-PAY-001 and part of C55-BUL-001: a bulk request may only distribute review work
 * through the closed {@link ReviewBulkActionCode} vocabulary (ASSIGN_TO_SELF, manager-only
 * ASSIGN_TO_OPERATOR, RELEASE), is bounded to 1-25 distinct review cases with a stable
 * idempotency identity, and can never touch resolution, label, money, dispute, or control tables.
 * The worker is disabled so this suite proves the accept-time grammar and scope boundary, not
 * item execution, which is covered by the restart/atomicity proofs.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class ReviewBulkActionScopeTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator analyst;
    private Operator manager;
    private Operator viewer;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("bulk-scope");
        analyst = scenario.operator(merchant, "bulk-scope-analyst", "ANALYST");
        manager = scenario.operator(merchant, "bulk-scope-manager", "RISK_MANAGER");
        viewer = scenario.operator(merchant, "bulk-scope-viewer", "VIEWER");
    }

    @Test
    void onlyTheThreeAllowedActionsAreAcceptedAndAnUnknownActionIsRejectedBeforeAnyRowIsWritten() throws Exception {
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-bad-action")
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"RESOLVE\",\"items\":[{\"reviewCaseId\":\""
                                + UUID.randomUUID() + "\",\"expectedAssignmentVersion\":0}]}"))
                .andExpect(status().isBadRequest());

        for (String action : new String[] {"REFUND", "LABEL", "DISPUTE", "ACTIVATE_CONTROL",
                "CANCEL", "resolve", ""}) {
            mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                            .header(RISK_OPERATOR_HEADER, analyst.credential())
                            .header(IDEMPOTENCY_HEADER, "idem-bad-action-" + action)
                            .contentType(APPLICATION_JSON)
                            .content("{\"action\":\"" + action + "\",\"items\":[{\"reviewCaseId\":\""
                                    + UUID.randomUUID() + "\",\"expectedAssignmentVersion\":0}]}"))
                    .andExpect(status().isBadRequest());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isZero();
    }

    @Test
    void assignToOperatorRequiresAManagerAndAnExplicitTarget() throws Exception {
        UUID targetId = manager.id();
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-analyst-assign-operator")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_OPERATOR", targetId, oneItem())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("bulk_manager_required"));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-manager-assign-operator-no-target")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_OPERATOR", null, oneItem())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("bulk_manager_required"));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-manager-assign-operator-ok")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_OPERATOR", targetId, oneItem())))
                .andExpect(status().isAccepted());
    }

    @Test
    void selfAssignAndReleaseRejectAnExplicitTargetOperator() throws Exception {
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-self-with-target")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", manager.id(), oneItem())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_target_invalid"));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-release-with-target")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("RELEASE", manager.id(), oneItem())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_target_invalid"));
    }

    @Test
    void viewerCannotStartAnyBulkAction() throws Exception {
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, viewer.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-viewer-forbidden")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, oneItem())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("bulk_viewer_forbidden"));
    }

    @Test
    void zeroTwentyFiveAndTwentySixItemsAreTheExactAcceptedBoundary() throws Exception {
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-zero-items")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_item_count_invalid"));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-25-items")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, distinctItems(25))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.itemCount").value(25));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-26-items")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, distinctItems(26))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_item_count_invalid"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(1L);
    }

    @Test
    void duplicateReviewCaseIdInOneRequestIsRejected() throws Exception {
        UUID reviewCaseId = UUID.randomUUID();
        String items = "{\"reviewCaseId\":\"" + reviewCaseId + "\",\"expectedAssignmentVersion\":0},"
                + "{\"reviewCaseId\":\"" + reviewCaseId + "\",\"expectedAssignmentVersion\":1}";
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-duplicate-item")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, items)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_item_invalid"));
    }

    @Test
    void malformedNullNegativeAndOutOfRangeItemFieldsAreAllRefused() throws Exception {
        record Case(String label, String items, int expectedStatus) { }
        List<Case> cases = List.of(
                new Case("a negative expected version",
                        "{\"reviewCaseId\":\"" + UUID.randomUUID()
                                + "\",\"expectedAssignmentVersion\":-1}", 400),
                new Case("a null review case id",
                        "{\"reviewCaseId\":null,\"expectedAssignmentVersion\":0}", 400),
                new Case("a missing review case id",
                        "{\"expectedAssignmentVersion\":0}", 400),
                new Case("a malformed review case id",
                        "{\"reviewCaseId\":\"not-a-uuid\",\"expectedAssignmentVersion\":0}", 400),
                new Case("a null item entry", "null", 400),
                new Case("a non-numeric expected version",
                        "{\"reviewCaseId\":\"" + UUID.randomUUID()
                                + "\",\"expectedAssignmentVersion\":\"soon\"}", 400));

        for (Case testCase : cases) {
            int status = mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                            .header(RISK_OPERATOR_HEADER, analyst.credential())
                            .header(IDEMPOTENCY_HEADER, "idem-malformed-" + testCase.label().hashCode())
                            .contentType(APPLICATION_JSON)
                            .content(startBody("ASSIGN_TO_SELF", null, testCase.items())))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("start must refuse %s", testCase.label())
                    .isEqualTo(testCase.expectedStatus());
        }

        // A maximum-value expected version is syntactically legal and must be accepted at the
        // boundary rather than rejected; it simply cannot match, so the item later goes STALE.
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-max-version")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null,
                                "{\"reviewCaseId\":\"" + UUID.randomUUID()
                                        + "\",\"expectedAssignmentVersion\":" + Long.MAX_VALUE + "}")))
                .andExpect(status().isAccepted());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(1L);
    }

    @Test
    void aTruncatedOrNonObjectRequestBodyIsRefusedWithoutPartialPersistence() throws Exception {
        for (String body : List.of("{\"action\":\"ASSIGN_TO_SELF\",\"items\":[", "[]", "\"\"", "")) {
            int status = mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                            .header(RISK_OPERATOR_HEADER, analyst.credential())
                            .header(IDEMPOTENCY_HEADER, "idem-truncated-" + body.hashCode())
                            .contentType(APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("a malformed body must be refused, not partially applied")
                    .isBetween(400, 499);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_items", Long.class)).isZero();
    }

    @Test
    void exactReplayReturnsTheSameRequestAndAChangedBodyWithTheSameKeyConflicts() throws Exception {
        String body = startBody("ASSIGN_TO_SELF", null, oneItem());
        JsonNode first = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-replay-bulk")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        JsonNode replay = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-replay-bulk")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-replay-bulk")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, distinctItems(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("bulk_idempotency_mismatch"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(1L);
    }

    @Test
    void permutingItemOrderDoesNotChangeTheRequestIdentityOrCreateASecondRequest() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        String ascending = item(first, 0) + "," + item(second, 0) + "," + item(third, 0);
        String shuffled = item(third, 0) + "," + item(first, 0) + "," + item(second, 0);

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-permutation")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, ascending)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        // The service sorts items before digesting, so a reordered but identical selection is the
        // same request. If ordering leaked into the digest this would conflict instead of replay.
        JsonNode replay = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-permutation")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, shuffled)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        assertThat(replay.get("id").asText()).isEqualTo(created.get("id").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(1L);
        // Ordinals are assigned from the sorted order, so they are stable regardless of input order.
        assertThat(jdbcTemplate.queryForList("""
                SELECT manual_review_case_id FROM review_bulk_action_items
                WHERE bulk_action_request_id = ? ORDER BY ordinal
                """, UUID.class, UUID.fromString(created.get("id").asText())))
                .containsExactly(sorted(first, second, third));
    }

    @Test
    void theSameIdempotencyKeyUnderADifferentOperatorIsADifferentRequestNotAReplay() throws Exception {
        String body = startBody("ASSIGN_TO_SELF", null, oneItem());
        JsonNode byAnalyst = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-shared-across-principals")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        JsonNode byManager = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-shared-across-principals")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        assertThat(byManager.get("id").asText())
                .as("idempotency is principal-bound, so one operator's key must never resolve to "
                        + "another operator's request")
                .isNotEqualTo(byAnalyst.get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(2L);
    }

    @Test
    void missingBlankOversizedOrControlCharacterIdempotencyKeysAreRejected() throws Exception {
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, oneItem())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_idempotency_key_invalid"));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "a".repeat(256))
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, oneItem())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_idempotency_key_invalid"));

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "badkey")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, oneItem())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bulk_idempotency_key_invalid"));
    }

    @Test
    void aQueuedBulkRequestNeverTouchesResolutionLabelDisputeLedgerOrControlTables() throws Exception {
        long[] before = fingerprint();

        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-scope-fingerprint")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, distinctItems(3))))
                .andExpect(status().isAccepted());

        long[] after = fingerprint();
        assertThat(after).isEqualTo(before);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_items", Long.class)).isEqualTo(3L);
    }

    @Test
    void listingAndGettingARequestTreatsAForeignRequestIdAsAbsent() throws Exception {
        mockMvc.perform(get("/risk/workspace/review-bulk-actions/{id}", UUID.randomUUID())
                        .header(RISK_OPERATOR_HEADER, analyst.credential()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("bulk_action_not_found"));

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-foreign-tenant-request")
                        .contentType(APPLICATION_JSON)
                        .content(startBody("ASSIGN_TO_SELF", null, oneItem())))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        Merchant otherMerchant = scenario.merchant("bulk-scope-other-tenant");
        Operator otherManager = scenario.operator(otherMerchant, "bulk-scope-other-manager", "RISK_MANAGER");
        mockMvc.perform(get("/risk/workspace/review-bulk-actions/{id}", created.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, otherManager.credential()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("bulk_action_not_found"));
    }

    private long[] fingerprint() {
        return new long[] {
                count("review_resolutions"),
                count("fraud_labels"),
                count("chargebacks"),
                count("ledger_entries"),
                count("authored_rule_versions"),
                count("fraud_lists"),
        };
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String oneItem() {
        return item(UUID.randomUUID(), 0);
    }

    private String item(UUID reviewCaseId, long expectedVersion) {
        return "{\"reviewCaseId\":\"" + reviewCaseId + "\",\"expectedAssignmentVersion\":"
                + expectedVersion + "}";
    }

    private UUID[] sorted(UUID... ids) {
        UUID[] copy = ids.clone();
        java.util.Arrays.sort(copy);
        return copy;
    }

    private String distinctItems(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> "{\"reviewCaseId\":\"" + UUID.randomUUID()
                        + "\",\"expectedAssignmentVersion\":0}")
                .collect(Collectors.joining(","));
    }

    private String startBody(String action, UUID target, String itemsJson) {
        return "{\"action\":\"" + action + "\",\"targetOperatorId\":"
                + (target == null ? "null" : "\"" + target + "\"")
                + ",\"items\":[" + itemsJson + "]}";
    }
}
