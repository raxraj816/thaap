package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Proves C54-REL-001. A related-payment pivot is the single most dangerous read in a fraud
 * console: it turns one investigation into a claim about several payments. It must therefore
 * only relate on identity the server itself derived, must say which relation types it could not
 * evaluate, and must never widen the analyst's window past what it declared.
 */
@ActiveProfiles("test")
@SpringBootTest
class RelatedPaymentContractTests {

    private static final List<String> ALL_RELATION_REASONS = List.of("SAME_CUSTOMER",
            "SAME_CARD_FINGERPRINT", "SAME_EMAIL", "SAME_PHONE", "SAME_DEVICE", "SAME_SESSION",
            "SAME_IP");

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

    private RiskWorkspaceScenario scenario;
    private MockMvc mockMvc;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator operator;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("related-contract");
        operator = scenario.operator(merchant, "related-contract-key", "RISK_MANAGER");
    }

    @Test
    void twoPaymentsSharingServerDerivedIdentityAreRelatedAndAThirdIsNot() throws Exception {
        RiskWorkspaceScenario.Contact shared =
                new RiskWorkspaceScenario.Contact("ring@example.com", "+14155550101");
        RiskWorkspaceScenario.Payment seed = ringPayment("ring-a", shared,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        RiskWorkspaceScenario.Payment sibling = ringPayment("ring-b", shared,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        RiskWorkspaceScenario.Payment stranger = ringPayment("ring-c",
                new RiskWorkspaceScenario.Contact("stranger@example.com", "+14155559999"),
                RiskWorkspaceScenario.ALTERNATE_CARD_NUMBER);
        scenario.drainProjection();

        JsonNode page = related(seed.paymentIntentId(), "");
        List<String> returned = identifiers(page);

        // Non-vacuous first: the pivot must actually find the sibling before its exclusion of the
        // stranger means anything.
        assertThat(returned).contains(sibling.paymentIntentId().toString());
        assertThat(returned).doesNotContain(stranger.paymentIntentId().toString());
        assertThat(returned).doesNotContain(seed.paymentIntentId().toString());
        assertThat(page.get("scope").asText()).isEqualTo("SAME_MERCHANT_SERVER_DERIVED");
    }

    @Test
    void everyReturnedReasonIsOneTheServerDerivedAndTheCountMatchesTheList() throws Exception {
        RiskWorkspaceScenario.Contact shared =
                new RiskWorkspaceScenario.Contact("reasons@example.com", "+14155550202");
        RiskWorkspaceScenario.Payment seed = ringPayment("reasons-a", shared,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        ringPayment("reasons-b", shared, RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.drainProjection();

        JsonNode page = related(seed.paymentIntentId(), "");
        assertThat(page.get("data")).isNotEmpty();
        page.get("data").forEach(row -> {
            List<String> reasons = new ArrayList<>();
            row.get("reasons").forEach(reason -> reasons.add(reason.asText()));
            assertThat(reasons).isNotEmpty().doesNotHaveDuplicates()
                    .isSubsetOf(ALL_RELATION_REASONS);
            // The count is what an analyst reads as strength of association, so it cannot drift
            // from the list it summarises.
            assertThat(row.get("matchedReasonCount").asInt()).isEqualTo(reasons.size());
            assertThat(Instant.parse(row.get("firstObservedAt").asText()))
                    .isBeforeOrEqualTo(Instant.parse(row.get("lastObservedAt").asText()));
        });

        // Sharing an email and a phone across two separately created customers is precisely the
        // correlation a customer-id join would miss.
        List<String> allReasons = new ArrayList<>();
        page.get("data").forEach(row -> row.get("reasons").forEach(r -> allReasons.add(r.asText())));
        assertThat(allReasons).contains("SAME_EMAIL", "SAME_PHONE", "SAME_CARD_FINGERPRINT");
    }

    @Test
    void coveragePartitionsTheRelationVocabularyWithNoOverlapAndNoOmission() throws Exception {
        RiskWorkspaceScenario.Payment seed = scenario.allowedPayment(merchant, "coverage-seed");
        scenario.drainProjection();

        JsonNode coverage = related(seed.paymentIntentId(), "").get("coverage");
        List<String> available = new ArrayList<>();
        coverage.get("availableReasons").forEach(reason -> available.add(reason.asText()));
        List<String> unavailable = new ArrayList<>();
        coverage.get("unavailableReasons").forEach(reason -> unavailable.add(reason.asText()));

        // Silence about a relation type the seed had no identity for would read as "no matches",
        // which is a different and far more dangerous claim than "could not evaluate".
        assertThat(available).doesNotHaveDuplicates();
        assertThat(unavailable).doesNotHaveDuplicates();
        assertThat(available).doesNotContainAnyElementsOf(unavailable);
        List<String> union = new ArrayList<>(available);
        union.addAll(unavailable);
        assertThat(union).containsExactlyInAnyOrderElementsOf(ALL_RELATION_REASONS);
        if (unavailable.isEmpty()) {
            assertThat(coverage.get("state").asText()).isEqualTo("AVAILABLE");
        } else {
            assertThat(coverage.get("state").asText()).isEqualTo("PARTIAL");
            assertThat(coverage.get("reasonCode").asText())
                    .isEqualTo("seed_identity_types_unavailable");
        }
    }

    @Test
    void thePageDeclaresTheExactWindowItSearchedRatherThanAnImpliedOne() throws Exception {
        RiskWorkspaceScenario.Payment seed = scenario.allowedPayment(merchant, "window-seed");
        scenario.drainProjection();

        JsonNode page = related(seed.paymentIntentId(), "");
        Instant from = Instant.parse(page.get("from").asText());
        Instant to = Instant.parse(page.get("to").asText());
        Instant asOf = Instant.parse(page.get("asOf").asText());

        assertThat(from).isBefore(to);
        assertThat(to).isBeforeOrEqualTo(asOf);
        assertThat(Duration.between(from, to)).isLessThanOrEqualTo(Duration.ofDays(90));
        assertThat(page.get("seedPaymentIntentId").asText())
                .isEqualTo(seed.paymentIntentId().toString());
    }

    @Test
    void aWindowWiderThanTheProductPromiseOrPointingAtTheFutureIsRefused() throws Exception {
        RiskWorkspaceScenario.Payment seed = scenario.allowedPayment(merchant, "range-seed");
        Instant now = DatabaseInstant.now();

        expectRefusal(seed.paymentIntentId(),
                "?from=" + now.minus(Duration.ofDays(120)) + "&to=" + now);
        expectRefusal(seed.paymentIntentId(),
                "?from=" + now + "&to=" + now.minus(Duration.ofDays(1)));
        expectRefusal(seed.paymentIntentId(),
                "?from=" + now.minus(Duration.ofDays(1)) + "&to=" + now.plus(Duration.ofDays(30)));
        // An empty window would return nothing while looking like a completed search.
        expectRefusal(seed.paymentIntentId(), "?from=" + now + "&to=" + now);
    }

    @Test
    void theResultCeilingIsBoundedSoOnePivotCannotEnumerateTheMerchant() throws Exception {
        RiskWorkspaceScenario.Payment seed = scenario.allowedPayment(merchant, "limit-seed");

        assertThat(scenario.json(get("/risk/workspace/payments/" + seed.paymentIntentId()
                + "/related?limit=0")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400))
                .isNotNull();
        assertThat(scenario.json(get("/risk/workspace/payments/" + seed.paymentIntentId()
                + "/related?limit=51")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400))
                .isNotNull();
        JsonNode bounded = related(seed.paymentIntentId(), "?limit=50");
        assertThat(bounded.get("data").size()).isLessThanOrEqualTo(50);
    }

    @Test
    void everyRelatedRowNamesTheStateOfTheProjectionItSummarised() throws Exception {
        RiskWorkspaceScenario.Contact shared =
                new RiskWorkspaceScenario.Contact("state@example.com", "+14155550303");
        RiskWorkspaceScenario.Payment seed = ringPayment("state-a", shared,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        ringPayment("state-b", shared, RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.drainProjection();

        JsonNode page = related(seed.paymentIntentId(), "");
        assertThat(page.get("data")).isNotEmpty();
        page.get("data").forEach(row -> {
            // The row is an anchor fact joined to a read model. If the read model has not caught
            // up, the analyst must be told rather than shown a blank lifecycle.
            assertThat(row.get("sectionState").asText()).isIn("AVAILABLE", "EMPTY", "PARTIAL",
                    "STALE", "UNAVAILABLE", "NOT_APPLICABLE", "DENIED");
            assertThat(row.get("paymentAndDecision").get("coverageStatus").asText()).isNotBlank();
        });
    }

    private RiskWorkspaceScenario.Payment ringPayment(String prefix,
            RiskWorkspaceScenario.Contact contact, String cardNumber) throws Exception {
        return scenario.payment(merchant, prefix, RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD",
                1, contact, cardNumber);
    }

    private JsonNode related(UUID seedPaymentId, String query) throws Exception {
        return scenario.json(get("/risk/workspace/payments/" + seedPaymentId + "/related" + query)
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
    }

    private void expectRefusal(UUID seedPaymentId, String query) throws Exception {
        JsonNode error = scenario.json(get("/risk/workspace/payments/" + seedPaymentId
                + "/related" + query)
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400);
        assertThat(error.get("error").asText()).contains("invalid");
    }

    private List<String> identifiers(JsonNode page) {
        List<String> identifiers = new ArrayList<>();
        page.get("data").forEach(row -> identifiers.add(row.get("paymentIntentId").asText()));
        return identifiers;
    }
}
