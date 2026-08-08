package com.thaap.payments.investigation;

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
import com.thaap.payments.time.DatabaseInstant;
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
 * Proves the C55-SAV-001 API contract: payment-investigation saved views are a distinct typed
 * resource with owner/visibility authorization, idempotent mutation, optimistic-version
 * conflict, and explicit not-found/upgrade behavior. It does not touch or reinterpret the
 * existing review saved-search rows or routes.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationSavedViewContractTests {

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
    private Operator owner;
    private Operator otherAnalyst;
    private Operator manager;
    private Operator viewer;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("saved-view-owner");
        owner = scenario.operator(merchant, "saved-view-owner-op", "ANALYST");
        otherAnalyst = scenario.operator(merchant, "saved-view-other-op", "ANALYST");
        manager = scenario.operator(merchant, "saved-view-manager-op", "RISK_MANAGER");
        viewer = scenario.operator(merchant, "saved-view-viewer-op", "VIEWER");
    }

    @Test
    void createListUpdateDisableAndExecuteRoundTripThroughLiveOwnerAndVersion() throws Exception {
        JsonNode created = createView(owner, "idem-create-1", "My Payments",
                "PRIVATE", emptyFilterBody());
        assertThat(created.get("name").asText()).isEqualTo("My Payments");
        assertThat(created.get("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(created.get("lifecycleStatus").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("lifecycleVersion").asLong()).isEqualTo(1L);
        assertThat(created.has("filter")).isTrue();
        String viewId = created.get("id").asText();

        mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(viewId));

        String updatedResponse = mockMvc.perform(post("/risk/workspace/payment-views/{id}", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-update-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"name\":\"Renamed\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode updatedBody = objectMapper.readTree(updatedResponse);
        assertThat(updatedBody.get("name").asText()).isEqualTo("Renamed");
        assertThat(updatedBody.get("lifecycleVersion").asLong()).isEqualTo(2L);

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/disable", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-disable-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("DISABLED"));

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateViewIsInvisibleAndInexecutableToAnotherOperator() throws Exception {
        JsonNode created = createView(owner, "idem-private-1", "Private Only",
                "PRIVATE", emptyFilterBody());
        String viewId = created.get("id").asText();

        mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, otherAnalyst.credential()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, otherAnalyst.credential()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/disable", viewId)
                        .header(RISK_OPERATOR_HEADER, otherAnalyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-private-disable-attempt")
                        .contentType(APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantSharedViewRequiresManagerToCreateButIsReadableByAnyOperator() throws Exception {
        mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-shared-forbidden")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Team View\",\"visibility\":\"MERCHANT_SHARED\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("saved_view_manager_required"));

        JsonNode shared = createView(manager, "idem-shared-1", "Team View",
                "MERCHANT_SHARED", emptyFilterBody());
        String viewId = shared.get("id").asText();

        mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, viewer.credential()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(viewId));

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, viewer.credential()))
                .andExpect(status().isOk());

        // A non-owner analyst may read and execute a shared view but not manage it.
        mockMvc.perform(post("/risk/workspace/payment-views/{id}/disable", viewId)
                        .header(RISK_OPERATOR_HEADER, otherAnalyst.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-shared-disable-forbidden")
                        .contentType(APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/disable", viewId)
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-shared-disable-1")
                        .contentType(APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("DISABLED"));
    }

    @Test
    void staleExpectedVersionIsRefusedRatherThanSilentlyReapplied() throws Exception {
        JsonNode created = createView(owner, "idem-stale-1", "Stale Target",
                "PRIVATE", emptyFilterBody());
        String viewId = created.get("id").asText();

        mockMvc.perform(post("/risk/workspace/payment-views/{id}", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-stale-update-wrong-version")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedVersion\":99,\"name\":\"Nope\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("saved_view_stale"));
    }

    @Test
    void mutationsRequireAnIdempotencyKeyAndExactReplayReturnsTheSameRecord() throws Exception {
        mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"No Key\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("idempotency_key_required"));

        JsonNode first = createView(owner, "idem-replay-1", "Replayed", "PRIVATE", emptyFilterBody());
        JsonNode second = objectMapper.readTree(mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-replay-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Replayed\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());

        long countByThatKey = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_investigation_saved_views WHERE name = 'Replayed'",
                Long.class);
        assertThat(countByThatKey).isEqualTo(1L);
    }

    @Test
    void invalidNameSchemaAndVisibilityInputsAreRejectedBeforeAnyRowIsWritten() throws Exception {
        mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-blank-name")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("saved_view_name_invalid"));

        mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-control-name")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"bad\\u0007name\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("saved_view_name_invalid"));

        mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-null-visibility")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Ok\",\"filter\":" + emptyFilterBody() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("saved_view_visibility_invalid"));

        mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-bad-range")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Ok\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":{\"createdFrom\":\"2026-06-01T00:00:00Z\","
                                + "\"createdTo\":\"2026-01-01T00:00:00Z\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("investigation_filter_invalid"));

        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_investigation_saved_views", Long.class);
        assertThat(total).isZero();
    }

    @Test
    void aForeignOrAbsentViewIdIsNotFoundRatherThanDisclosingExistence() throws Exception {
        String foreignId = "00000000-0000-0000-0000-000000000099";
        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", foreignId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("saved_view_not_found"));

        Merchant otherMerchant = scenario.merchant("saved-view-other-tenant");
        Operator otherTenantManager = scenario.operator(otherMerchant, "other-tenant-op", "RISK_MANAGER");
        JsonNode foreignTenantView = createView(otherTenantManager, "idem-other-tenant-1",
                "Other Tenant View", "MERCHANT_SHARED", emptyFilterBody());
        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", foreignTenantView.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("saved_view_not_found"));
    }

    @Test
    void aDisabledOwnerCanNoLongerReadOrExecuteTheViewItCreated() throws Exception {
        JsonNode created = createView(owner, "idem-owner-disable", "Owned By Soon Disabled",
                "PRIVATE", emptyFilterBody());
        String viewId = created.get("id").asText();

        mockMvc.perform(post("/risk/operators/{id}/disable", owner.id())
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(IDEMPOTENCY_HEADER, "idem-disable-view-owner"))
                .andExpect(status().isOk());

        // A previously valid operator credential must stop working the moment the operator is
        // disabled; a saved view is not a standing grant that outlives its owner's authority.
        mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().is4xxClientError());

        // The row itself is untouched: disabling an operator is not a silent delete of their work.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM payment_investigation_saved_views WHERE id = ?
                """, String.class, java.util.UUID.fromString(viewId))).isEqualTo("ACTIVE");
    }

    @Test
    void anUnsupportedStoredSchemaVersionRequiresAnExplicitUpgradeRatherThanBroadeningTheQuery()
            throws Exception {
        JsonNode created = createView(owner, "idem-schema-drift", "Schema Drift",
                "PRIVATE", emptyFilterBody());
        java.util.UUID viewId = java.util.UUID.fromString(created.get("id").asText());

        // Simulate a definition written by a future build. The decoder is closed and table-driven,
        // so it must refuse rather than fall back to an unfiltered (broader) query.
        jdbcTemplate.update("""
                UPDATE payment_investigation_saved_views SET filter_schema_version = ? WHERE id = ?
                """, "payment_investigation_filter_v99", viewId);

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("saved_view_upgrade_required"));

        mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("saved_view_upgrade_required"));
    }

    @Test
    void aTamperedDefinitionDigestRefusesExecutionRatherThanRunningTheAlteredFilter()
            throws Exception {
        JsonNode created = createView(owner, "idem-digest-tamper", "Digest Tamper",
                "PRIVATE", emptyFilterBody());
        java.util.UUID viewId = java.util.UUID.fromString(created.get("id").asText());

        jdbcTemplate.update("""
                UPDATE payment_investigation_saved_views SET definition_digest = ? WHERE id = ?
                """, "0".repeat(64), viewId);

        mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("saved_view_upgrade_required"));
    }

    @Test
    void aProjectionGenerationSwapInvalidatesResultsButNeverDisablesAValidSavedView()
            throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "sv-generation");
        scenario.drainProjection();

        JsonNode created = createView(owner, "idem-generation-swap", "Generation Swap",
                "PRIVATE", emptyFilterBody());
        java.util.UUID viewId = java.util.UUID.fromString(created.get("id").asText());

        String beforeIds = mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(beforeIds).contains(reviewed.paymentIntentId().toString());

        // Move the projected rows onto a generation the platform no longer advertises as active.
        java.util.UUID otherGeneration = insertNonActiveGeneration();
        jdbcTemplate.update("""
                UPDATE payment_risk_projections SET projection_generation_id = ?
                WHERE merchant_id = ?
                """, otherGeneration, merchant.id());

        // The definition is still valid and still executable: only its results narrow. A
        // generation change must never be mistaken for a corrupt or retired saved view.
        String afterBody = mockMvc.perform(post("/risk/workspace/payment-views/{id}/execute", viewId)
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterBody).doesNotContain(reviewed.paymentIntentId().toString());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM payment_investigation_saved_views WHERE id = ?
                """, String.class, viewId))
                .as("a generation swap must not disable the saved definition")
                .isEqualTo("ACTIVE");
        mockMvc.perform(get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, owner.credential()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(viewId.toString()));
    }

    private java.util.UUID insertNonActiveGeneration() {
        java.util.Map<String, Object> active = jdbcTemplate.queryForMap("""
                SELECT generation.projection_definition_id AS definition_id,
                       generation.generation_number AS generation_number,
                       generation.handler_version AS handler_version
                FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                WHERE definition.projection_name = 'payment-risk-v1'
                """);
        java.util.UUID generationId = java.util.UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_generations (id, projection_definition_id,
                        generation_number, handler_version, build_source, lifecycle_status,
                        created_at)
                VALUES (?, ?, ?, ?, 'REBUILD', 'BUILDING', ?)
                """, generationId, active.get("definition_id"),
                ((Number) active.get("generation_number")).longValue() + 1,
                active.get("handler_version"),
                java.sql.Timestamp.from(DatabaseInstant.now()));
        return generationId;
    }

    @Test
    void existingReviewSavedSearchRoutesAndRowsAreUntouchedByPaymentInvestigationViews() throws Exception {
        createView(owner, "idem-coexist-1", "Investigation View", "PRIVATE", emptyFilterBody());

        mockMvc.perform(post("/risk/workspace/saved-searches")
                        .header(RISK_OPERATOR_HEADER, owner.credential())
                        .header(IDEMPOTENCY_HEADER, "idem-review-search-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Review Search\",\"visibility\":\"PRIVATE\",\"filter\":{}}"))
                .andExpect(status().isCreated());

        long reviewSearchCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_saved_searches", Long.class);
        long investigationViewCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_investigation_saved_views", Long.class);
        assertThat(reviewSearchCount).isEqualTo(1L);
        assertThat(investigationViewCount).isEqualTo(1L);
    }

    private JsonNode createView(Operator operator, String idempotencyKey, String name,
            String visibility, String filterJson) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, operator.credential())
                        .header(IDEMPOTENCY_HEADER, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\","
                                + "\"filter\":" + filterJson + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String emptyFilterBody() {
        return "{}";
    }
}
