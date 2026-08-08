package com.thaap.payments.bulk;

import static com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER;
import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import com.thaap.payments.time.DatabaseInstant;
import java.util.List;
import java.util.Map;
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

/**
 * Proves C55-DAT-001. A saved view is a definition, never a snapshot of results: it stores no
 * cursor and no projection row, and executing it binds the currently active generation. A bulk
 * item preserves the requested, observed, and final assignment versions that explain its outcome,
 * and it never creates or reinterprets a fraud label, decision, feature, or analytics fact.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class InvestigationWorkflowLineageTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private ReviewBulkActionStore store;
    @Autowired private ReviewBulkItemExecutor itemExecutor;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("workflow-lineage");
        manager = scenario.operator(merchant, "workflow-lineage-manager", "RISK_MANAGER");
    }

    @Test
    void aSavedViewStoresItsDefinitionAndNeverACursorOrAProjectionResult() throws Exception {
        scenario.allowedPayment(merchant, "lineage-view-seed");
        scenario.drainProjection();
        UUID viewId = createView("Definition only");

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT * FROM payment_investigation_saved_views WHERE id = ?
                """, viewId);
        // The stored column set is the whole argument: a cursor or a result column would let a
        // view present rows captured at save time as if they were current truth.
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "merchant_id",
                "owner_operator_id", "name", "visibility", "filter_schema_version",
                "canonical_filter_json", "sort_code", "visible_columns_json", "definition_digest",
                "lifecycle_status", "lifecycle_version", "created_at", "updated_at", "disabled_at");

        // Executing the view returns live projection freshness rather than anything stored.
        JsonNode executed = execute(viewId);
        assertThat(executed.has("freshness")).isTrue();
        UUID activeGeneration = jdbcTemplate.queryForObject("""
                SELECT generation.id
                FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                 AND generation.projection_definition_id = definition.id
                WHERE definition.projection_name = 'payment-risk-v1'
                  AND generation.lifecycle_status = 'ACTIVE'
                """, UUID.class);
        assertThat(UUID.fromString(executed.at("/freshness/generationId").asText()))
                .as("execution must bind the generation that is active now, not one recorded "
                        + "when the view was saved")
                .isEqualTo(activeGeneration);

        // The definition digest is unchanged by executing it.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT definition_digest FROM payment_investigation_saved_views WHERE id = ?
                """, String.class, viewId)).isEqualTo(row.get("definition_digest"));
    }

    @Test
    void aBulkItemPreservesTheRequestedObservedAndFinalVersionsThatExplainItsOutcome()
            throws Exception {
        Payment succeeding = scenario.reviewedPayment(merchant, "lineage-ok");
        Payment stale = scenario.reviewedPayment(merchant, "lineage-stale");

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), manager.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("lineage-idem"),
                sixtyFourChars("lineage-digest"), 2, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId,
                succeeding.reviewCaseId(), 0, 1, DatabaseInstant.now());
        // Requesting version 5 against a case still at 0 is the stale partition.
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId,
                stale.reviewCaseId(), 5, 2, DatabaseInstant.now());

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "lineage-worker");
        ReviewBulkActionStore.ClaimedItem item;
        while ((item = store.claimNext(lease)) != null) {
            itemExecutor.execute(item);
        }
        store.finalizeRequest(lease);

        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT ordinal, lifecycle_status, expected_assignment_version,
                       observed_assignment_version, final_assignment_version, failure_code
                FROM review_bulk_action_items WHERE bulk_action_request_id = ? ORDER BY ordinal
                """, requestId);

        Map<String, Object> succeeded = items.get(0);
        assertThat(succeeded.get("lifecycle_status")).isEqualTo("SUCCEEDED");
        assertThat(number(succeeded.get("expected_assignment_version"))).isZero();
        assertThat(number(succeeded.get("final_assignment_version")))
                .as("a successful item records the version its own mutation produced")
                .isEqualTo(1L);
        assertThat(succeeded.get("failure_code")).isNull();

        Map<String, Object> refused = items.get(1);
        assertThat(refused.get("lifecycle_status")).isEqualTo("STALE");
        assertThat(number(refused.get("expected_assignment_version"))).isEqualTo(5L);
        assertThat(number(refused.get("observed_assignment_version")))
                .as("a stale item must keep the version it actually observed, which is the only "
                        + "evidence that explains the refusal to the analyst")
                .isZero();
        assertThat(refused.get("final_assignment_version")).isNull();
        assertThat(refused.get("failure_code")).isEqualTo("assignment_version_stale");
    }

    @Test
    void aBulkAssignmentCreatesNoFraudLabelDecisionFeatureOrAnalyticsFact() throws Exception {
        Payment payment = scenario.reviewedPayment(merchant, "lineage-no-analytics");
        scenario.drainProjection();

        Map<String, Long> before = analyticsFingerprint();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), manager.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("no-analytics-idem"),
                sixtyFourChars("no-analytics-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId,
                payment.reviewCaseId(), 0, 1, DatabaseInstant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "no-analytics-worker");
        itemExecutor.execute(store.claimNext(lease));
        store.finalizeRequest(lease);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId))
                .as("the fingerprint comparison is vacuous unless the assignment really happened")
                .isEqualTo("SUCCEEDED");
        assertThat(analyticsFingerprint())
                .as("distributing review work must not create or reinterpret a label, decision, "
                        + "feature, or projection fact")
                .isEqualTo(before);
    }

    private Map<String, Long> analyticsFingerprint() {
        return Map.of(
                "fraud_labels", count("fraud_labels"),
                "fraud_decisions", count("fraud_decisions"),
                "feature_snapshots", count("feature_snapshots"),
                "review_resolutions", count("review_resolutions"),
                "payment_risk_projections", count("payment_risk_projections"));
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private UUID createView(String name) throws Exception {
        JsonNode created = objectMapper.readTree(mockMvc.perform(
                        post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "lineage-view-" + name)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":{\"fraudActions\":[\"ALLOW\"]}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(created.get("id").asText());
    }

    private JsonNode execute(UUID viewId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Deterministic exactly-64-character value for columns the schema pins at CHAR_LENGTH 64. */
    private String sixtyFourChars(String seed) {
        StringBuilder value = new StringBuilder(seed);
        while (value.length() < 64) value.append('0');
        return value.substring(0, 64);
    }
}
