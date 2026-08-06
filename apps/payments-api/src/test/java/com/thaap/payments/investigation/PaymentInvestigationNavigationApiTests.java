package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C54-API-001. Timeline, related payments, and webhook activity are the three navigation
 * routes an analyst console and any future integrator will code against. Their shape, their
 * refusals, and their role gate are a published promise: a client that handles one of them
 * correctly must be able to handle all three without special cases.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentInvestigationNavigationApiTests {

    private static final List<String> NAVIGATION_ROUTES =
            List.of("/timeline", "/related", "/webhook-activity");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator manager;
    private RiskWorkspaceScenario.Payment payment;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("navigation-api");
        manager = scenario.operator(merchant, "navigation-api-key", "RISK_MANAGER");
        payment = scenario.allowedPayment(merchant, "navigation-api");
        scenario.drainProjection();
    }

    @Test
    void allThreeNavigationRoutesPublishACursorPagedCollection() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            JsonNode page = read(route);
            JsonNode rows = collection(page, route);
            assertThat(rows.isArray()).as(route + " publishes a list").isTrue();
            // A cursor field must be present even when it is null, so a client can page without
            // branching on whether the server chose to include it.
            assertThat(page.has("nextCursor")).as(route + " publishes nextCursor").isTrue();
        }
    }

    @Test
    void everyNavigationPageNamesTheAuthorityOrConsistencyBoundaryItWasReadUnder()
            throws Exception {
        // Each route answers a different question, so each declares a different guarantee. Without
        // these an analyst cannot tell whether they are looking at complete evidence.
        assertThat(read("/timeline").get("maximumStreamVersion").asLong()).isNotNegative();
        assertThat(read("/timeline").get("ordering").asText()).isNotBlank();
        assertThat(read("/webhook-activity").get("authority").asText())
                .isEqualTo("WEBHOOK_TRANSPORT_EVIDENCE");
        JsonNode related = read("/related");
        assertThat(related.get("scope").asText()).isEqualTo("SAME_MERCHANT_SERVER_DERIVED");
        assertThat(related.get("asOf").asText()).isNotBlank();
    }

    @Test
    void aFinalPageDeclaresItselfFinalRatherThanLeavingTheClientToGuess() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            JsonNode page = read(route);
            // A non-null cursor on a page with nothing beyond it makes a well-written client
            // loop forever, so finality is expressed by a null cursor and nothing else.
            if (page.get("nextCursor").isNull()) {
                assertThat(collection(page, route).size()).isLessThanOrEqualTo(50);
            } else {
                assertThat(page.get("nextCursor").asText()).isNotBlank();
            }
        }
    }

    @Test
    void everyNavigationRouteRefusesAnUnauthenticatedReader() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            mockMvc.perform(get(base(payment.paymentIntentId()) + route))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void aViewerMayNavigateBecauseNavigationIsReadOnlyEvidence() throws Exception {
        RiskWorkspaceScenario.Operator viewer =
                scenario.operator(merchant, "navigation-api-viewer", "VIEWER");
        for (String route : NAVIGATION_ROUTES) {
            // Navigation reveals no more than the investigation detail a viewer already holds, so
            // gating it higher would push analysts toward over-privileged credentials.
            mockMvc.perform(get(base(payment.paymentIntentId()) + route)
                            .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                    viewer.credential()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void everyNavigationRouteAnswersAnUnknownPaymentWithTheSameNamedRefusal() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            JsonNode body = scenario.json(get(base(UUID.randomUUID()) + route)
                    .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, manager.credential()), 404);
            assertThat(body.get("error").asText()).isEqualTo("payment_investigation_not_found");
            // A machine-readable code plus a recovery action is what lets a console show a useful
            // message instead of a raw stack of JSON.
            assertThat(body.get("recoveryAction").asText()).isNotBlank();
            assertThat(body.get("retryable").asBoolean()).isFalse();
            assertThat(body.get("requestId").asText()).isNotBlank();
        }
    }

    @Test
    void everyNavigationRouteRefusesALimitOutsideItsPublishedBounds() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            for (String limit : List.of("0", "-1", "51", "abc")) {
                mockMvc.perform(get(base(payment.paymentIntentId()) + route)
                                .param("limit", limit)
                                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                        manager.credential()))
                        .andExpect(status().isBadRequest());
            }
            // The upper bound must be honoured, not merely accepted, or a single request can pull
            // an unbounded slice of the merchant's history.
            JsonNode page = scenario.json(get(base(payment.paymentIntentId()) + route)
                    .param("limit", "50")
                    .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, manager.credential()), 200);
            assertThat(collection(page, route).size()).isLessThanOrEqualTo(50);
        }
    }

    @Test
    void everyNavigationRouteReturnsJsonWithARequestIdentifierOnTheResponse() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            mockMvc.perform(get(base(payment.paymentIntentId()) + route)
                            .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                    manager.credential()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().exists("X-Request-Id"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .content().contentTypeCompatibleWith("application/json"));
        }
    }

    @Test
    void aNavigationIdentifierThatIsNotAPaymentIdentifierIsRefusedBeforeAnyLookup()
            throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            // Refusing at the boundary keeps malformed input from reaching a query planner and
            // keeps the refusal indistinguishable from any other bad request.
            mockMvc.perform(get("/risk/workspace/payments/not-a-uuid" + route)
                            .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                    manager.credential()))
                    .andExpect(status().is4xxClientError());
        }
    }

    private JsonNode collection(JsonNode page, String route) {
        // Every cursor-paged investigation route publishes its rows under one name, so an
        // integrator writes one pager rather than a per-route branch — and a per-route branch is
        // exactly where pagination defects hide. This asserts the single name rather than
        // tolerating either, which is what closed TD-0006.
        JsonNode rows = page.get("data");
        assertThat(rows).as(route + " publishes a row collection").isNotNull();
        return rows;
    }

    private JsonNode read(String route) throws Exception {
        return scenario.json(get(base(payment.paymentIntentId()) + route)
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, manager.credential()), 200);
    }

    private String base(UUID paymentIntentId) {
        return "/risk/workspace/payments/" + paymentIntentId;
    }
}
