package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * Proves C54-TEN-001. Identity digests are derived from a platform-wide correlation key, so two
 * merchants who charge the same stolen card hold byte-identical anchors. The tenant boundary is
 * therefore the only thing standing between an investigation and a cross-merchant disclosure, and
 * every relationship read must enforce it at the query rather than in the caller.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentRelationshipTenantIsolationTests {

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
    private RiskWorkspaceScenario.Merchant alpha;
    private RiskWorkspaceScenario.Operator alphaOperator;
    private RiskWorkspaceScenario.Merchant beta;
    private RiskWorkspaceScenario.Operator betaOperator;
    private RiskWorkspaceScenario.Payment alphaPayment;
    private RiskWorkspaceScenario.Payment betaPayment;

    /** The same human, the same card, charged at two unrelated merchants. */
    private static final RiskWorkspaceScenario.Contact SHARED_IDENTITY =
            new RiskWorkspaceScenario.Contact("shared.victim@example.com", "+14155551212");

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        alpha = scenario.merchant("relationship-alpha");
        alphaOperator = scenario.operator(alpha, "relationship-alpha-key", "RISK_MANAGER");
        beta = scenario.merchant("relationship-beta");
        betaOperator = scenario.operator(beta, "relationship-beta-key", "RISK_MANAGER");
        alphaPayment = scenario.payment(alpha, "tenant-alpha",
                RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD", 1, SHARED_IDENTITY,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        betaPayment = scenario.payment(beta, "tenant-beta",
                RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD", 1, SHARED_IDENTITY,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.drainProjection();
    }

    @Test
    void twoMerchantsHoldingTheSameIdentityDigestsAreStillNeverRelatedToEachOther()
            throws Exception {
        // Non-vacuous first. If the digests differed, the tenant filter would never be exercised
        // and this test would pass for the wrong reason.
        Long distinctDigests = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT relation_identity_hmac)
                FROM payment_investigation_relation_anchors
                WHERE relation_type = 'SAME_CARD_FINGERPRINT'
                  AND payment_intent_id IN (?, ?)
                """, Long.class, alphaPayment.paymentIntentId(), betaPayment.paymentIntentId());
        assertThat(distinctDigests).isEqualTo(1L);

        List<String> alphaRelated = identifiers(related(alphaPayment.paymentIntentId(),
                alphaOperator));
        List<String> betaRelated = identifiers(related(betaPayment.paymentIntentId(),
                betaOperator));

        assertThat(alphaRelated).doesNotContain(betaPayment.paymentIntentId().toString());
        assertThat(betaRelated).doesNotContain(alphaPayment.paymentIntentId().toString());
    }

    @Test
    void aForeignPaymentIsIndistinguishableFromAPaymentThatNeverExisted() throws Exception {
        UUID absent = UUID.randomUUID();
        for (String suffix : List.of("", "/timeline", "/related", "/webhook-activity")) {
            int foreignStatus = statusFor(alphaPayment.paymentIntentId(), suffix, betaOperator);
            int absentStatus = statusFor(absent, suffix, betaOperator);
            // A different status code for "yours but hidden" versus "does not exist" is a payment
            // enumeration oracle across the whole merchant base.
            assertThat(foreignStatus).as("route " + suffix).isEqualTo(absentStatus);

            String foreignBody = bodyFor(alphaPayment.paymentIntentId(), suffix, betaOperator);
            String absentBody = bodyFor(absent, suffix, betaOperator);
            assertThat(scrub(foreignBody, alphaPayment.paymentIntentId()))
                    .as("route " + suffix).isEqualTo(scrub(absentBody, absent));
        }
    }

    @Test
    void coverageIsComputedInsideTheTenantAndNeverBorrowsAForeignAnchor() throws Exception {
        JsonNode alphaCoverage = related(alphaPayment.paymentIntentId(), alphaOperator)
                .get("coverage");
        List<String> available = new ArrayList<>();
        alphaCoverage.get("availableReasons").forEach(reason -> available.add(reason.asText()));

        Long alphaAnchorTypes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT relation_type) FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, Long.class, alpha.id(), alphaPayment.paymentIntentId());

        // SAME_CUSTOMER is always derivable from the intent itself, so the declared coverage is
        // exactly the tenant's own anchor types plus that one.
        assertThat(available).hasSize(alphaAnchorTypes.intValue() + 1);
        assertThat(available).contains("SAME_CUSTOMER");
    }

    @Test
    void aRelatedCursorMintedInOneTenantIsRefusedInTheOther() throws Exception {
        RiskWorkspaceScenario.Contact ring =
                new RiskWorkspaceScenario.Contact("cursor.ring@example.com", "+14155551313");
        RiskWorkspaceScenario.Payment seed = scenario.payment(alpha, "cursor-ring-a",
                RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD", 1, ring,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.payment(alpha, "cursor-ring-b", RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD",
                1, ring, RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.payment(alpha, "cursor-ring-c", RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD",
                1, ring, RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.drainProjection();

        JsonNode firstPage = scenario.json(get("/risk/workspace/payments/"
                + seed.paymentIntentId() + "/related?limit=1")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, alphaOperator.credential()),
                200);
        String cursor = firstPage.get("nextCursor").isNull() ? null
                : firstPage.get("nextCursor").asText();
        assertThat(cursor).as("the ring must page so a cursor exists to steal").isNotNull();

        JsonNode refusal = scenario.json(get("/risk/workspace/payments/"
                + betaPayment.paymentIntentId() + "/related?limit=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8))
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, betaOperator.credential()),
                400);
        assertThat(refusal.get("error").asText()).contains("cursor_invalid");
    }

    @Test
    void theAnchorTableItselfNeverCarriesARowUnderTheWrongMerchant() {
        Long misfiled = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors anchors
                JOIN payment_intents intents ON intents.id = anchors.payment_intent_id
                WHERE anchors.merchant_id <> intents.merchant_id
                """, Long.class);
        assertThat(misfiled).isZero();

        Long alphaRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, Long.class, alpha.id(), alphaPayment.paymentIntentId());
        assertThat(alphaRows).isPositive();
    }

    private JsonNode related(UUID seedPaymentId, RiskWorkspaceScenario.Operator reader)
            throws Exception {
        return scenario.json(get("/risk/workspace/payments/" + seedPaymentId + "/related")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, reader.credential()), 200);
    }

    private int statusFor(UUID paymentId, String suffix, RiskWorkspaceScenario.Operator reader)
            throws Exception {
        return mockMvc.perform(get("/risk/workspace/payments/" + paymentId + suffix)
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, reader.credential()))
                .andReturn().getResponse().getStatus();
    }

    private String bodyFor(UUID paymentId, String suffix, RiskWorkspaceScenario.Operator reader)
            throws Exception {
        return mockMvc.perform(get("/risk/workspace/payments/" + paymentId + suffix)
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, reader.credential()))
                .andReturn().getResponse().getContentAsString();
    }

    private String scrub(String body, UUID paymentId) {
        // The request identifier and timestamp are per-request by design and carry no information
        // about the payment. Everything else must be byte-identical.
        return body.replace(paymentId.toString(), "{payment}")
                .replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"{request}\"")
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"{timestamp}\"");
    }

    private List<String> identifiers(JsonNode page) {
        List<String> identifiers = new ArrayList<>();
        page.get("data").forEach(row -> identifiers.add(row.get("paymentIntentId").asText()));
        return identifiers;
    }
}
