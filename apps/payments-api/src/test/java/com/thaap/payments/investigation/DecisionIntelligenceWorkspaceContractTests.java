package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.bulk.ReviewBulkDispatcher;
import com.thaap.payments.event.DomainEvent;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.projection.ProjectionProcessingOutcome;
import com.thaap.payments.projection.TransportedDomainEvent;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class DecisionIntelligenceWorkspaceContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private ReviewBulkDispatcher bulkDispatcher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void oneWorkspaceReconcilesSourceTimelineRelationshipsSearchBulkAndInsights() throws Exception {
        MerchantAccess owner = createMerchant("decision-intelligence-owner");
        MerchantAccess foreign = createMerchant("decision-intelligence-foreign");
        OperatorAccess manager = createOperator(owner, "workspace-manager", "RISK_MANAGER");
        OperatorAccess foreignManager = createOperator(foreign, "foreign-manager", "RISK_MANAGER");
        ReviewFixture first = createReview(owner, "workspace-first");
        ReviewFixture second = createReview(owner, "workspace-second");
        processProjectionEvents();

        JsonNode detail = json(get("/risk/workspace/payments/{id}", first.paymentIntentId())
                .header(RISK_KEY, manager.secret()), 200);
        assertThat(detail.at("/sourceSnapshot/consistency").asText()).isEqualTo("REPEATABLE_READ");
        assertThat(detail.at("/sectionStatuses/paymentLifecycle/state").asText()).isEqualTo("AVAILABLE");
        assertThat(detail.at("/decisionExplanation/riskScoreBps").asInt()).isBetween(0, 10_000);
        assertThat(detail.at("/decisionExplanation/modelState").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(detail.at("/decisionExplanation/controlEvaluations").isArray()).isTrue();
        assertThat(detail.at("/decisionExplanation/precedence/winningControl").asText())
                .isEqualTo("REVIEW_REPEATED_ATTEMPT_V1");
        assertThat(detail.at("/decisionExplanation/controlEvaluations").findValues(
                "contributedToWinningAction").stream().filter(JsonNode::asBoolean)).hasSize(1);
        assertThat(detail.toString()).doesNotContain("@example.com", "cardNumber",
                "relationIdentityHmac", "idempotency");
        mockMvc.perform(get("/risk/workspace/payments/{id}", first.paymentIntentId())
                        .header(RISK_KEY, foreignManager.secret()))
                .andExpect(status().isNotFound());

        JsonNode timelineOne = json(get("/risk/workspace/payments/{id}/timeline",
                first.paymentIntentId()).header(RISK_KEY, manager.secret()).param("limit", "1"), 200);
        assertThat(timelineOne.get("data")).hasSize(1);
        assertThat(timelineOne.at("/data/0/streamVersion").asLong()).isOne();
        assertThat(timelineOne.get("nextCursor").asText()).isNotBlank();
        assertThat(timelineOne.toString()).doesNotContain("customerReference", "@example.com");
        JsonNode timelineTwo = json(get("/risk/workspace/payments/{id}/timeline",
                first.paymentIntentId()).header(RISK_KEY, manager.secret()).param("limit", "1")
                .param("cursor", timelineOne.get("nextCursor").asText()), 200);
        assertThat(timelineTwo.at("/data/0/streamVersion").asLong()).isEqualTo(2);
        mockMvc.perform(get("/risk/workspace/payments/{id}/timeline", second.paymentIntentId())
                        .header(RISK_KEY, manager.secret())
                        .param("cursor", timelineOne.get("nextCursor").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("workspace_cursor_invalid"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION));

        JsonNode related = json(get("/risk/workspace/payments/{id}/related",
                first.paymentIntentId()).header(RISK_KEY, manager.secret()), 200);
        assertThat(related.get("scope").asText()).isEqualTo("SAME_MERCHANT_SERVER_DERIVED");
        assertThat(related.get("data")).isNotEmpty();
        assertThat(related.toString()).contains(second.paymentIntentId().toString(),
                "SAME_CARD_FINGERPRINT").doesNotContain("hmac", "@example.com");

        JsonNode search = json(post("/risk/workspace/payments/search")
                .header(RISK_KEY, manager.secret()).param("limit", "1")
                .contentType(APPLICATION_JSON)
                .content("{\"filter\":{\"fraudActions\":[\"REVIEW\"]},\"sort\":\"CREATED_AT_DESC\"}"), 200);
        assertThat(search.get("data")).hasSize(1);
        assertThat(search.get("canonicalFilterJson").asText()).doesNotContain("sql", "url");

        String savedViewBody = """
                {"name":"Review decisions","visibility":"PRIVATE",
                 "filter":{"fraudActions":["REVIEW"]},"sort":"CREATED_AT_DESC",
                 "visibleColumns":["CREATED_AT","FRAUD_ACTION","COVERAGE"]}
                """;
        JsonNode saved = json(post("/risk/workspace/payment-views")
                .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "save-payment-view")
                .contentType(APPLICATION_JSON).content(savedViewBody), 201);
        assertThat(saved.get("filterSchemaVersion").asText())
                .isEqualTo(PaymentInvestigationFilterCodec.SCHEMA_VERSION);
        assertThat(saved.get("definitionDigest").asText()).hasSize(64);
        JsonNode executed = json(post("/risk/workspace/payment-views/{id}/execute",
                saved.get("id").asText()).header(RISK_KEY, manager.secret()), 200);
        assertThat(executed.get("data")).hasSize(2);

        JsonNode bulk = json(post("/risk/workspace/review-bulk-actions")
                .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "mixed-bulk")
                .contentType(APPLICATION_JSON).content("""
                        {"action":"ASSIGN_TO_SELF","items":[
                          {"reviewCaseId":"%s","expectedAssignmentVersion":0},
                          {"reviewCaseId":"%s","expectedAssignmentVersion":99}]}
                        """.formatted(first.reviewCaseId(), second.reviewCaseId())), 202);
        UUID bulkId = UUID.fromString(bulk.get("id").asText());
        JsonNode replay = json(post("/risk/workspace/review-bulk-actions")
                .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "mixed-bulk")
                .contentType(APPLICATION_JSON).content("""
                        {"action":"ASSIGN_TO_SELF","items":[
                          {"reviewCaseId":"%s","expectedAssignmentVersion":0},
                          {"reviewCaseId":"%s","expectedAssignmentVersion":99}]}
                        """.formatted(first.reviewCaseId(), second.reviewCaseId())), 202);
        assertThat(replay.get("id").asText()).isEqualTo(bulkId.toString());
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "mixed-bulk")
                        .contentType(APPLICATION_JSON).content("""
                                {"action":"RELEASE","items":[
                                  {"reviewCaseId":"%s","expectedAssignmentVersion":0}]}
                                """.formatted(first.reviewCaseId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("bulk_idempotency_mismatch"));
        bulkDispatcher.launchSynchronously(bulkId);
        JsonNode terminal = json(get("/risk/workspace/review-bulk-actions/{id}", bulkId)
                .header(RISK_KEY, manager.secret()), 200);
        assertThat(terminal.get("lifecycleStatus").asText()).isEqualTo("PARTIAL");
        assertThat(terminal.get("succeededCount").asInt()).isOne();
        assertThat(terminal.get("failedCount").asInt()).isOne();
        JsonNode itemTruth = json(get("/risk/workspace/review-bulk-actions/{id}/items", bulkId)
                .header(RISK_KEY, manager.secret()), 200);
        assertThat(values(itemTruth.get("data"), "lifecycleStatus"))
                .containsExactlyInAnyOrder("SUCCEEDED", "STALE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_assignment_events
                WHERE manual_review_case_id = ?
                """, Integer.class, first.reviewCaseId())).isOne();
        JsonNode timelineAfterAssignment = json(get("/risk/workspace/payments/{id}/timeline",
                first.paymentIntentId()).header(RISK_KEY, manager.secret()), 200);
        assertThat(timelineAfterAssignment.toString()).contains("manual_review.assigned")
                .doesNotContain("assigneeReference", manager.id().toString());

        JsonNode decisionInsights = json(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_KEY, manager.secret()), 200);
        assertThat(decisionInsights.get("claimBoundary").asText()).isEqualTo("DESCRIPTIVE_NOT_CAUSAL");
        assertThat(decisionInsights.at("/rates/labelCoverage/denominator").isNumber()).isTrue();
        JsonNode reviewInsights = json(get("/risk/workspace/insights/review-operations")
                .header(RISK_KEY, manager.secret()), 200);
        assertThat(reviewInsights.get("authority").asText()).isEqualTo("AUTHORITATIVE_REVIEW_SOURCE");
        assertThat(reviewInsights.get("conflictedAttempts").isNull()).isTrue();
        assertThat(reviewInsights.get("sourceAsOf").asText()).isNotBlank();
    }

    @Test
    void bulkGrammarRolesAndLiveActorRechecksFailClosed() throws Exception {
        MerchantAccess merchant = createMerchant("bulk-authorization-owner");
        OperatorAccess viewer = createOperator(merchant, "bulk-viewer", "VIEWER");
        OperatorAccess analyst = createOperator(merchant, "bulk-analyst", "ANALYST");
        OperatorAccess manager = createOperator(merchant, "bulk-manager", "RISK_MANAGER");
        ReviewFixture review = createReview(merchant, "bulk-review");

        String oneItem = """
                {"action":"ASSIGN_TO_SELF","items":[
                  {"reviewCaseId":"%s","expectedAssignmentVersion":0}]}
                """.formatted(review.reviewCaseId());
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "viewer-bulk")
                        .contentType(APPLICATION_JSON).content(oneItem))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("bulk_viewer_forbidden"));
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-other")
                        .contentType(APPLICATION_JSON).content("""
                                {"action":"ASSIGN_TO_OPERATOR","targetOperatorId":"%s","items":[
                                  {"reviewCaseId":"%s","expectedAssignmentVersion":0}]}
                                """.formatted(manager.id(), review.reviewCaseId())))
                .andExpect(status().isForbidden());
        List<String> duplicated = new ArrayList<>();
        for (int index = 0; index < 26; index++) {
            duplicated.add("{\"reviewCaseId\":\"" + UUID.randomUUID()
                    + "\",\"expectedAssignmentVersion\":0}");
        }
        mockMvc.perform(post("/risk/workspace/review-bulk-actions")
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "too-many")
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"ASSIGN_TO_SELF\",\"items\":["
                                + String.join(",", duplicated) + "]}"))
                .andExpect(status().isBadRequest());

        JsonNode queued = json(post("/risk/workspace/review-bulk-actions")
                .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "disable-before-work")
                .contentType(APPLICATION_JSON).content(oneItem), 202);
        UUID requestId = UUID.fromString(queued.get("id").asText());
        mockMvc.perform(post("/risk/operators/{id}/disable", analyst.id())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "disable-analyst"))
                .andExpect(status().isOk());
        bulkDispatcher.launchSynchronously(requestId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT assignment_version FROM manual_review_cases WHERE id = ?
                """, Long.class, review.reviewCaseId())).isZero();
    }

    private void processProjectionEvents() {
        List<DomainEvent> events = eventRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(DomainEvent::getRecordedAt)
                        .thenComparing(DomainEvent::getMerchantId)
                        .thenComparing(DomainEvent::getStreamId)
                        .thenComparingLong(DomainEvent::getStreamVersion))
                .toList();
        long offset = 0;
        for (DomainEvent event : events) {
            TransportedDomainEvent transported = new TransportedDomainEvent(
                    PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, offset++,
                    TenantQualifiedStreamKey.encode(event.getMerchantId(), event.getStreamType(),
                            event.getStreamId()), event.getEventRecordBytes(), event.getId(),
                    event.getMerchantId(), event.getEventRecordSha256());
            assertThat(projectionConsumer.process(transported))
                    .isIn(ProjectionProcessingOutcome.APPLIED, ProjectionProcessingOutcome.DUPLICATE);
        }
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = json(post("/admin/merchants")
                .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"), 201);
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role)
            throws Exception {
        JsonNode response = json(post("/risk/operators").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"), 201);
        return new OperatorAccess(UUID.fromString(response.get("id").asText()),
                response.get("credential").asText());
    }

    private ReviewFixture createReview(MerchantAccess merchant, String prefix) throws Exception {
        JsonNode customer = json(post("/customers").header(API_KEY, merchant.apiKey())
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + prefix + "@example.com\"}"), 201);
        UUID customerId = UUID.fromString(customer.get("id").asText());
        JsonNode method = json(post("/payment_methods").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, prefix + "-method").contentType(APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId
                        + "\",\"cardNumber\":\"4999990000000003\"}"), 201);
        UUID methodId = UUID.fromString(method.get("id").asText());
        JsonNode intent = json(post("/payment_intents").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, prefix + "-intent").contentType(APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId
                        + "\",\"amount\":99999,\"currency\":\"USD\",\"captureMethod\":\"MANUAL\"}"), 201);
        UUID intentId = UUID.fromString(intent.get("id").asText());
        confirm(merchant, intentId, methodId, prefix + "-confirm-1");
        confirm(merchant, intentId, methodId, prefix + "-confirm-2");
        confirm(merchant, intentId, methodId, prefix + "-confirm-3");
        UUID reviewCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM manual_review_cases WHERE payment_intent_id = ?",
                UUID.class, intentId);
        return new ReviewFixture(intentId, reviewCaseId);
    }

    private void confirm(MerchantAccess merchant, UUID intentId, UUID methodId, String key)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int statusCode) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString());
    }

    private List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.get(field).asText()));
        return values;
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record ReviewFixture(UUID paymentIntentId, UUID reviewCaseId) { }
}
