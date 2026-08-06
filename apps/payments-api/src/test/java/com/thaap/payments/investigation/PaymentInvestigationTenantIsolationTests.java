package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-TEN-001: every workspace route derives the merchant from the live operator
 * context, and a copied foreign identifier discloses no existence, count, or route. The two
 * merchants are built with identical shape so a leak cannot hide behind an incidental
 * difference in the fixture.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationTenantIsolationTests {

    private static final List<String> WORKSPACE_PAYMENT_ROUTES = List.of(
            "/risk/workspace/payments/{id}",
            "/risk/workspace/payments/{id}/timeline",
            "/risk/workspace/payments/{id}/related",
            "/risk/workspace/payments/{id}/webhook-activity");

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
    private Merchant owner;
    private Merchant intruder;
    private Operator intruderManager;
    private Payment ownedPayment;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        owner = scenario.merchant("isolation-owner");
        intruder = scenario.merchant("isolation-intruder");
        scenario.operator(owner, "isolation-owner-manager", "RISK_MANAGER");
        intruderManager = scenario.operator(intruder, "isolation-intruder-manager", "RISK_MANAGER");
        // Equal-shaped tenants: the same payment recipe on both sides, so an isolation break
        // cannot be masked by one tenant simply having no comparable row.
        ownedPayment = scenario.reviewedPayment(owner, "isolation-owned");
        scenario.reviewedPayment(intruder, "isolation-intruder-own");
        scenario.drainProjection();
    }

    @ParameterizedTest(name = "{0} denies a copied foreign payment id")
    @ValueSource(strings = {
            "/risk/workspace/payments/{id}",
            "/risk/workspace/payments/{id}/timeline",
            "/risk/workspace/payments/{id}/related",
            "/risk/workspace/payments/{id}/webhook-activity"})
    void everyPaymentScopedRouteTreatsAForeignIdExactlyLikeAnAbsentId(String route)
            throws Exception {
        String foreignResponse = mockMvc.perform(get(route, ownedPayment.paymentIntentId())
                        .header(RISK_OPERATOR_HEADER, intruderManager.credential()))
                .andReturn().getResponse().getContentAsString();
        int foreignStatus = mockMvc.perform(get(route, ownedPayment.paymentIntentId())
                        .header(RISK_OPERATOR_HEADER, intruderManager.credential()))
                .andReturn().getResponse().getStatus();

        UUID neverExisted = UUID.randomUUID();
        String absentResponse = mockMvc.perform(get(route, neverExisted)
                        .header(RISK_OPERATOR_HEADER, intruderManager.credential()))
                .andReturn().getResponse().getContentAsString();
        int absentStatus = mockMvc.perform(get(route, neverExisted)
                        .header(RISK_OPERATOR_HEADER, intruderManager.credential()))
                .andReturn().getResponse().getStatus();

        assertThat(foreignStatus).as("%s foreign status", route).isEqualTo(404).isEqualTo(absentStatus);
        // Byte equality once the caller's own inputs are masked: any difference in wording,
        // field set, or error code would let an intruder enumerate payments in other tenants.
        assertThat(comparableError(foreignResponse, ownedPayment.paymentIntentId()))
                .as("%s discloses existence", route)
                .isEqualTo(comparableError(absentResponse, neverExisted));
        assertThat(foreignResponse).doesNotContain(owner.id().toString());
    }

    @Test
    void aForeignChildIdentifierCannotBeSubstitutedIntoASucceedingOwnRequest() throws Exception {
        Payment intruderOwn = scenario.reviewedPayment(intruder, "isolation-substitution");
        scenario.drainProjection();

        // The intruder legitimately reads its own payment, then swaps in each of the victim's
        // child identifiers. A join that follows a bare UUID would resolve one of these.
        UUID foreignAttemptId = jdbcTemplate.queryForObject("""
                SELECT id FROM payment_attempts
                WHERE payment_intent_id = ? ORDER BY attempt_number DESC LIMIT 1
                """, UUID.class, ownedPayment.paymentIntentId());
        UUID foreignDecisionId = jdbcTemplate.queryForObject("""
                SELECT fraud_decision_id FROM payment_attempts WHERE id = ?
                """, UUID.class, foreignAttemptId);
        UUID foreignReviewCaseId = ownedPayment.reviewCaseId();

        for (UUID foreignChildId : List.of(foreignAttemptId, foreignDecisionId,
                foreignReviewCaseId)) {
            mockMvc.perform(get("/risk/workspace/payments/{id}", foreignChildId)
                            .header(RISK_OPERATOR_HEADER, intruderManager.credential()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
        }

        JsonNode ownDetail = scenario.json(get("/risk/workspace/payments/{id}",
                intruderOwn.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, intruderManager.credential()), 200);
        assertThat(ownDetail.toString())
                .doesNotContain(ownedPayment.paymentIntentId().toString(),
                        foreignAttemptId.toString(), foreignDecisionId.toString(),
                        foreignReviewCaseId.toString(), owner.id().toString());
    }

    @Test
    void searchAndRelatedNeverCountOrNameARowOutsideTheOperatorsMerchant() throws Exception {
        JsonNode search = scenario.json(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, intruderManager.credential())
                .contentType(APPLICATION_JSON)
                .content("{\"sort\":\"CREATED_AT_DESC\"}"), 200);

        List<UUID> intruderPaymentIds = jdbcTemplate.queryForList(
                "SELECT id FROM payment_intents WHERE merchant_id = ?", UUID.class, intruder.id());
        List<String> returnedIds = search.get("data").valueStream()
                .map(row -> row.get("paymentIntentId").asText()).toList();
        assertThat(returnedIds).containsExactlyInAnyOrderElementsOf(
                intruderPaymentIds.stream().map(UUID::toString).toList());
        assertThat(search.toString()).doesNotContain(ownedPayment.paymentIntentId().toString(),
                owner.id().toString());

        JsonNode related = scenario.json(get("/risk/workspace/payments/{id}/related",
                intruderPaymentIds.getFirst())
                .header(RISK_OPERATOR_HEADER, intruderManager.credential()), 200);
        assertThat(related.get("scope").asText()).isEqualTo("SAME_MERCHANT_SERVER_DERIVED");
        assertThat(related.toString()).doesNotContain(ownedPayment.paymentIntentId().toString(),
                owner.id().toString());
    }

    @Test
    void aCursorMintedForOneTenantIsRejectedRatherThanReplayedForAnother() throws Exception {
        Operator ownerManager = scenario.operator(owner, "isolation-cursor-owner", "RISK_MANAGER");
        JsonNode ownerPage = scenario.json(get("/risk/workspace/payments/{id}/timeline",
                ownedPayment.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, ownerManager.credential()).param("limit", "1"), 200);
        String ownerCursor = ownerPage.get("nextCursor").asText();
        assertThat(ownerCursor).isNotBlank();

        Payment intruderOwn = scenario.reviewedPayment(intruder, "isolation-cursor-intruder");
        // The cursor is bound to a tenant and a payment. Replaying it against a payment the
        // intruder does own must fail closed rather than page through borrowed positions.
        mockMvc.perform(get("/risk/workspace/payments/{id}/timeline",
                        intruderOwn.paymentIntentId())
                        .header(RISK_OPERATOR_HEADER, intruderManager.credential())
                        .param("cursor", ownerCursor))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    void routesRejectAnUnauthenticatedOrDisabledOperatorBeforeReadingAnyPaymentRow()
            throws Exception {
        for (String route : WORKSPACE_PAYMENT_ROUTES) {
            mockMvc.perform(get(route, ownedPayment.paymentIntentId()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s without a credential", route).isEqualTo(401));
            mockMvc.perform(get(route, ownedPayment.paymentIntentId())
                            .header(RISK_OPERATOR_HEADER, "not-a-real-operator-credential"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s with a forged credential", route).isEqualTo(401));
        }

        Operator disabled = scenario.operator(owner, "isolation-disabled", "RISK_MANAGER");
        mockMvc.perform(post("/risk/operators/{id}/disable", disabled.id())
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, owner.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, "isolation-disable"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        // Authorization is rechecked against live operator state, not against the credential
        // the caller still holds from before the disable.
        mockMvc.perform(get("/risk/workspace/payments/{id}", ownedPayment.paymentIntentId())
                        .header(RISK_OPERATOR_HEADER, disabled.credential()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    /**
     * Masks only what the caller already supplied or can trivially observe: its own request id,
     * the response timestamp, and the identifier it typed into the path. Everything else must
     * match, because everything else is server knowledge.
     */
    private String comparableError(String body, UUID requestedPaymentIntentId) {
        return body.replaceAll("\"requestId\"\\s*:\\s*\"[^\"]*\"", "\"requestId\":\"<masked>\"")
                .replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\"", "\"timestamp\":\"<masked>\"")
                .replace(requestedPaymentIntentId.toString(), "<requested-id>");
    }
}
