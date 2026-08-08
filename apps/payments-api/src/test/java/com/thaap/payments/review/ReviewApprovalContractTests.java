package com.thaap.payments.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class ReviewApprovalContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}/{3}")
    @MethodSource("processorOutcomes")
    void approveContinuesSameReviewedAttemptAcrossEveryProcessorAndCaptureOutcome(
            String fixtureName, String cardNumber, String expectedIntentStatus,
            String expectedAttemptStatus, String expectedAuthorizationStatus,
            String expectedResponseCode, String captureMethod, int expectedLedgerEntries)
            throws Exception {
        ApprovalFixture fixture = createApprovalFixture(fixtureName, cardNumber, captureMethod);
        assign(fixture, fixtureName + "-assign");
        String requestBody = approvalBody("INCONCLUSIVE");

        String firstResponse = mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.reviewCaseId())
                        .header(RISK_KEY, fixture.operatorSecret())
                        .header(IDEMPOTENCY_KEY, fixtureName + "-approve")
                        .contentType(APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replayResponse = mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.reviewCaseId())
                        .header(RISK_KEY, fixture.operatorSecret())
                        .header(IDEMPOTENCY_KEY, fixtureName + "-approve")
                .contentType(APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String distinctKeyReplayResponse = mockMvc.perform(
                        post("/risk/reviews/{id}/resolve", fixture.reviewCaseId())
                                .header(RISK_KEY, fixture.operatorSecret())
                                .header(IDEMPOTENCY_KEY, fixtureName + "-approve-distinct-key")
                                .contentType(APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(firstResponse);
        assertThat(objectMapper.readTree(replayResponse)).isEqualTo(response);
        JsonNode distinctKeyResponse = objectMapper.readTree(distinctKeyReplayResponse);
        if (response.get("nextAction") != null && !response.get("nextAction").isNull()) {
            assertThat(response.at("/nextAction/clientSecret").isTextual()).isTrue();
            assertThat(distinctKeyResponse.at("/nextAction/clientSecret").isNull()).isTrue();
            com.fasterxml.jackson.databind.node.ObjectNode firstWithoutSecret = response.deepCopy();
            com.fasterxml.jackson.databind.node.ObjectNode distinctWithoutSecret = distinctKeyResponse.deepCopy();
            firstWithoutSecret.withObject("/nextAction").remove("clientSecret");
            distinctWithoutSecret.withObject("/nextAction").remove("clientSecret");
            assertThat(distinctWithoutSecret).isEqualTo(firstWithoutSecret);
        } else {
            assertThat(distinctKeyResponse).isEqualTo(response);
        }
        assertThat(response.get("outcome").asText()).isEqualTo("APPROVE");
        assertThat(response.get("processorOutcome").asText()).isEqualTo(expectedAuthorizationStatus);
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo(expectedIntentStatus);
        assertThat(response.get("paymentAttemptStatus").asText()).isEqualTo(expectedAttemptStatus);
        assertThat(response.get("label").isNull()).isTrue();
        assertThat(response.get("authorizationId").isTextual()).isTrue();

        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.paymentIntentId()))
                .isEqualTo(expectedIntentStatus);
        assertThat(value("SELECT status FROM payment_attempts WHERE id = ?", fixture.paymentAttemptId()))
                .isEqualTo(expectedAttemptStatus);
        assertThat(value("SELECT status FROM manual_review_cases WHERE id = ?", fixture.reviewCaseId()))
                .isEqualTo("RESOLVED");
        assertThat(value("SELECT action FROM fraud_decisions WHERE id = ?", fixture.fraudDecisionId()))
                .isEqualTo("REVIEW");
        assertThat(value("SELECT status FROM authorizations WHERE payment_attempt_id = ?",
                fixture.paymentAttemptId())).isEqualTo(expectedAuthorizationStatus);
        assertThat(value("SELECT processor_response_code FROM authorizations WHERE payment_attempt_id = ?",
                fixture.paymentAttemptId())).isEqualTo(expectedResponseCode);
        assertThat(value("SELECT processor_reference_id FROM authorizations WHERE payment_attempt_id = ?",
                fixture.paymentAttemptId())).isEqualTo("auth_sim_"
                        + fixture.paymentAttemptId().toString().replace("-", ""));
        assertThat(count("payment_attempts")).isEqualTo(3);
        assertThat(count("fraud_decisions")).isEqualTo(3);
        assertThat(count("review_resolutions")).isOne();
        assertThat(count("authorizations")).isEqualTo(3);
        assertThat(count("fraud_labels")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries WHERE payment_intent_id = ?
                """, Integer.class, fixture.paymentIntentId())).isEqualTo(expectedLedgerEntries);
    }

    @Test
    void confirmedLegitimateApprovalCreatesOneTypedLabelWhileInvalidApprovalClassificationFails()
            throws Exception {
        ApprovalFixture fixture = createApprovalFixture(
                "confirmed-legitimate", "4999990000000002", "MANUAL");
        assign(fixture, "confirmed-legitimate-assign");

        mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.reviewCaseId())
                        .header(RISK_KEY, fixture.operatorSecret())
                        .header(IDEMPOTENCY_KEY, "invalid-approve-fraud")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"APPROVE",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(DatabaseInstant.now().minusSeconds(5))))
                .andExpect(status().isBadRequest());

        JsonNode resolution = objectMapper.readTree(mockMvc.perform(
                        post("/risk/reviews/{id}/resolve", fixture.reviewCaseId())
                                .header(RISK_KEY, fixture.operatorSecret())
                                .header(IDEMPOTENCY_KEY, "confirmed-legitimate-approve")
                                .contentType(APPLICATION_JSON)
                                .content(approvalBody("CONFIRMED_LEGITIMATE")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(resolution.get("label").get("labelValue").asText()).isEqualTo("LEGITIMATE");
        assertThat(resolution.get("label").get("fraudType").isNull()).isTrue();
        assertThat(value("SELECT label_value FROM fraud_labels")).isEqualTo("LEGITIMATE");
        assertThat(jdbcTemplate.queryForObject("SELECT fraud_type FROM fraud_labels", String.class)).isNull();
        assertThat(count("fraud_labels")).isOne();

        JsonNode labels = objectMapper.readTree(mockMvc.perform(get("/risk/labels")
                        .header(RISK_KEY, fixture.operatorSecret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(labels).hasSize(1);
        assertThat(labels.get(0).get("labelValue").asText()).isEqualTo("LEGITIMATE");
    }

    static Stream<Arguments> processorOutcomes() {
        return Stream.of(
                Arguments.of("approved-automatic", "4999990000000001", "CAPTURED",
                        "AUTHORIZED", "APPROVED", "APPROVED", "AUTOMATIC", 2),
                Arguments.of("approved-manual", "4999990000000001", "REQUIRES_CAPTURE",
                        "AUTHORIZED", "APPROVED", "APPROVED", "MANUAL", 0),
                Arguments.of("issuer-decline", "4999990000000002", "REQUIRES_PAYMENT_METHOD",
                        "DECLINED", "DECLINED", "ISSUER_DECLINE", "AUTOMATIC", 0),
                Arguments.of("insufficient-funds", "4999990000000003", "REQUIRES_PAYMENT_METHOD",
                        "DECLINED", "DECLINED", "INSUFFICIENT_FUNDS", "AUTOMATIC", 0),
                Arguments.of("suspected-fraud", "4999990000000004", "REQUIRES_PAYMENT_METHOD",
                        "DECLINED", "DECLINED", "SUSPECTED_FRAUD", "AUTOMATIC", 0),
                Arguments.of("cvc-failure", "4999990000000005", "REQUIRES_PAYMENT_METHOD",
                        "DECLINED", "DECLINED", "CVC_FAILURE", "AUTOMATIC", 0),
                Arguments.of("avs-line1-failure", "4999990000000006", "REQUIRES_PAYMENT_METHOD",
                        "DECLINED", "DECLINED", "AVS_LINE1_FAILURE", "AUTOMATIC", 0),
                Arguments.of("avs-postal-failure", "4999990000000007", "REQUIRES_PAYMENT_METHOD",
                        "DECLINED", "DECLINED", "AVS_POSTAL_FAILURE", "AUTOMATIC", 0),
                Arguments.of("processor-3ds", "4999990000000008", "REQUIRES_ACTION",
                        "REQUIRES_ACTION", "REQUIRES_ACTION", "THREE_DS_REQUIRED", "AUTOMATIC", 0),
                Arguments.of("default-approval", "4242424242424242", "CAPTURED",
                        "AUTHORIZED", "APPROVED", "APPROVED", "AUTOMATIC", 2));
    }

    private ApprovalFixture createApprovalFixture(String name, String targetCard, String captureMethod)
            throws Exception {
        MerchantAccess merchant = createMerchant("review-approval-" + name);
        UUID customerId = createCustomer(merchant, name + "@example.com");
        UUID declineMethodId = createPaymentMethod(
                merchant, customerId, name + "-decline-card", "4999990000000003");
        UUID targetMethodId = createPaymentMethod(
                merchant, customerId, name + "-target-card", targetCard);
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, name + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":99999,"currency":"USD",
                                 "captureMethod":"%s"}
                                """.formatted(customerId, captureMethod)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID intentId = UUID.fromString(intent.get("id").asText());
        confirm(merchant, intentId, declineMethodId, name + "-confirm-1");
        confirm(merchant, intentId, declineMethodId, name + "-confirm-2");
        JsonNode reviewedAttempt = confirm(merchant, intentId, targetMethodId, name + "-confirm-3");
        assertThat(reviewedAttempt.get("status").asText()).isEqualTo("IN_REVIEW");
        UUID reviewCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM manual_review_cases WHERE payment_intent_id = ?",
                UUID.class, intentId);
        OperatorAccess operator = createOperator(merchant, name + "-operator");
        return new ApprovalFixture(merchant, operator.secret(), intentId,
                UUID.fromString(reviewedAttempt.get("id").asText()),
                UUID.fromString(reviewedAttempt.get("fraudDecisionId").asText()), reviewCaseId);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()), response.get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String key, String cardNumber)
            throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"%s"}
                                """.formatted(customerId, cardNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"ANALYST\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(response.get("credential").asText());
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID paymentMethodId, String key)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void assign(ApprovalFixture fixture, String key) throws Exception {
        mockMvc.perform(post("/risk/reviews/{id}/assign", fixture.reviewCaseId())
                        .header(RISK_KEY, fixture.operatorSecret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"expectedAssignmentVersion\":0}"))
                .andExpect(status().isOk());
    }

    private String approvalBody(String classification) {
        return """
                {
                  "outcome":"APPROVE",
                  "classification":"%s",
                  "reasonCode":"MANUAL_REVIEW_APPROVED",
                  "evidenceOccurredAt":"%s"
                }
                """.formatted(classification, DatabaseInstant.now().minusSeconds(5));
    }

    private String value(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }

    private record OperatorAccess(String secret) {
    }

    private record ApprovalFixture(MerchantAccess merchant, String operatorSecret,
            UUID paymentIntentId, UUID paymentAttemptId, UUID fraudDecisionId, UUID reviewCaseId) {
    }
}
