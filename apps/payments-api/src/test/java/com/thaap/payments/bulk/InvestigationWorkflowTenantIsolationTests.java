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
 * Proves C55-TEN-001: every saved-view and bulk-assignment surface is merchant-qualified from the live risk-operator
 * context. Two merchants are built with deliberately identical shapes — same review case count,
 * same idempotency keys, same view names — so a query missing its tenant predicate would return
 * the neighbour's row rather than nothing, and a foreign identifier is indistinguishable from an
 * absent one down to the response bytes.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class InvestigationWorkflowTenantIsolationTests {

    private static final String SHARED_IDEMPOTENCY_KEY = "identical-key-in-both-tenants";
    private static final String SHARED_VIEW_NAME = "Identical View Name";

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
    private Tenant first;
    private Tenant second;

    private record Tenant(Merchant merchant, Operator manager, Payment payment) { }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        first = tenant("tenant-one");
        second = tenant("tenant-two");
    }

    private Tenant tenant(String name) throws Exception {
        Merchant merchant = scenario.merchant("workflow-isolation-" + name);
        Operator manager = scenario.operator(merchant, "workflow-isolation-" + name + "-mgr",
                "RISK_MANAGER");
        Payment payment = scenario.reviewedPayment(merchant, "workflow-isolation-" + name);
        return new Tenant(merchant, manager, payment);
    }

    @Test
    void identicalIdempotencyKeysInTwoTenantsCreateTwoIndependentRequests() throws Exception {
        JsonNode firstRequest = startBulk(first, SHARED_IDEMPOTENCY_KEY);
        JsonNode secondRequest = startBulk(second, SHARED_IDEMPOTENCY_KEY);

        assertThat(secondRequest.get("id").asText())
                .as("an idempotency key is scoped to its tenant and principal, so the same string "
                        + "in another merchant must never resolve to the first merchant's request")
                .isNotEqualTo(firstRequest.get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_bulk_action_requests", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT merchant_id) FROM review_bulk_action_requests", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void aForeignBulkRequestIdIsByteIdenticalToAnAbsentOne() throws Exception {
        JsonNode foreign = startBulk(second, "foreign-bulk-request");

        String foreignBody = scrub(mockMvc.perform(
                        get("/risk/workspace/review-bulk-actions/{id}", foreign.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, first.manager().credential()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        String absentBody = scrub(mockMvc.perform(
                        get("/risk/workspace/review-bulk-actions/{id}", UUID.randomUUID())
                        .header(RISK_OPERATOR_HEADER, first.manager().credential()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        assertThat(foreignBody)
                .as("a foreign request must not be distinguishable from an absent one")
                .isEqualTo(absentBody);
    }

    @Test
    void aForeignBulkItemsPageIsByteIdenticalToAnAbsentOne() throws Exception {
        JsonNode foreign = startBulk(second, "foreign-bulk-items");

        String foreignBody = scrub(mockMvc.perform(
                        get("/risk/workspace/review-bulk-actions/{id}/items",
                                foreign.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, first.manager().credential()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        String absentBody = scrub(mockMvc.perform(
                        get("/risk/workspace/review-bulk-actions/{id}/items", UUID.randomUUID())
                        .header(RISK_OPERATOR_HEADER, first.manager().credential()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        assertThat(foreignBody).isEqualTo(absentBody);
    }

    @Test
    void identicalViewNamesInTwoTenantsCoexistAndNeitherListingSeesTheOther() throws Exception {
        JsonNode firstView = createView(first);
        JsonNode secondView = createView(second);
        assertThat(secondView.get("id").asText()).isNotEqualTo(firstView.get("id").asText());

        JsonNode firstListing = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, first.manager().credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(firstListing).hasSize(1);
        assertThat(firstListing.get(0).get("id").asText()).isEqualTo(firstView.get("id").asText());

        JsonNode secondListing = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, second.manager().credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(secondListing).hasSize(1);
        assertThat(secondListing.get(0).get("id").asText()).isEqualTo(secondView.get("id").asText());
    }

    @Test
    void aBulkRequestCannotNameAnotherTenantsReviewCaseAsAnItem() throws Exception {
        UUID foreignReviewCaseId = second.payment().reviewCaseId();
        assertThat(foreignReviewCaseId).isNotNull();

        // Admission is deliberately permissive about whether the review exists — resolving it at
        // accept time would itself disclose existence. The tenant boundary is enforced at the
        // mutation boundary instead, so the request is accepted but the item can never succeed.
        JsonNode started = objectMapper.readTree(mockMvc.perform(
                        post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, first.manager().credential())
                        .header(IDEMPOTENCY_HEADER, "cross-tenant-item")
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"ASSIGN_TO_SELF\",\"items\":[{\"reviewCaseId\":\""
                                + foreignReviewCaseId + "\",\"expectedAssignmentVersion\":0}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        // The item row is written under the requesting tenant, never the target's.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT merchant_id FROM review_bulk_action_items WHERE bulk_action_request_id = ?
                """, UUID.class, UUID.fromString(started.get("id").asText())))
                .isEqualTo(first.merchant().id());

        // And the neighbour's review case is untouched by the attempt.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT assignment_version FROM manual_review_cases WHERE id = ?
                """, Long.class, foreignReviewCaseId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events WHERE manual_review_case_id = ?
                """, Long.class, foreignReviewCaseId)).isZero();
    }

    @Test
    void aCursorMintedInOneTenantIsRejectedInTheOther() throws Exception {
        JsonNode firstRequest = startBulk(first, "cursor-tenant-first", 3);

        JsonNode page = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/review-bulk-actions/{id}/items",
                                firstRequest.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, first.manager().credential())
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String cursor = page.get("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        JsonNode secondRequest = startBulk(second, "cursor-tenant-second", 3);
        mockMvc.perform(get("/risk/workspace/review-bulk-actions/{id}/items",
                        secondRequest.get("id").asText())
                        .header(RISK_OPERATOR_HEADER, second.manager().credential())
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    private JsonNode createView(Tenant tenant) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/risk/workspace/payment-views")
                        .header(RISK_OPERATOR_HEADER, tenant.manager().credential())
                        .header(IDEMPOTENCY_HEADER, SHARED_IDEMPOTENCY_KEY)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + SHARED_VIEW_NAME + "\",\"visibility\":\"PRIVATE\","
                                + "\"filter\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode startBulk(Tenant tenant, String idempotencyKey) throws Exception {
        return startBulk(tenant, idempotencyKey, 1);
    }

    private JsonNode startBulk(Tenant tenant, String idempotencyKey, int itemCount)
            throws Exception {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < itemCount; index++) {
            if (index > 0) items.append(',');
            items.append("{\"reviewCaseId\":\"").append(UUID.randomUUID())
                    .append("\",\"expectedAssignmentVersion\":0}");
        }
        return objectMapper.readTree(mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_OPERATOR_HEADER, tenant.manager().credential())
                        .header(IDEMPOTENCY_HEADER, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"ASSIGN_TO_SELF\",\"items\":[" + items + "]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
    }

    /** Removes the per-request varying fields so two refusals can be compared byte for byte. */
    private String scrub(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"*\"")
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"*\"")
                .replaceAll("\"path\":\"[^\"]*\"", "\"path\":\"*\"");
    }
}
