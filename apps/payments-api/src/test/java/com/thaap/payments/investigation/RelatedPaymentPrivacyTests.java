package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.security.CorrelationValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Proves C54-HMC-001. The relationship graph is built entirely from keyed identity digests. Those
 * digests are the platform's most sensitive derived data: they are stable across time and across
 * merchants, so a single leak turns every future investigation into a cross-merchant correlation
 * tool for whoever holds it. They must stay server-side, be domain-separated, and be
 * unreproducible without the correlation key.
 */
@ActiveProfiles("test")
@SpringBootTest
class RelatedPaymentPrivacyTests {

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
    private CorrelationValueHasher correlationValueHasher;

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
    private RiskWorkspaceScenario.Payment seed;

    private static final RiskWorkspaceScenario.Contact IDENTITY =
            new RiskWorkspaceScenario.Contact("digest.subject@example.com", "+14155552323");

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() throws Exception {
        // A shared cached context means a shared database. Without this the fixture
        // inherits the previous class's rows, and drainProjection replays them as if
        // they were its own. See ER-0102.
        databaseCleaner.clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("related-privacy");
        operator = scenario.operator(merchant, "related-privacy-key", "RISK_MANAGER");
        seed = scenario.payment(merchant, "digest-a", RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR,
                "USD", 1, IDENTITY, RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.payment(merchant, "digest-b", RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD", 1,
                IDENTITY, RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.drainProjection();
    }

    @Test
    void noIdentityDigestOrPlaintextEverCrossesTheInvestigationBoundary() throws Exception {
        Map<String, String> canaries = new LinkedHashMap<>();
        List<Map<String, Object>> anchors = jdbcTemplate.queryForList("""
                SELECT relation_type, relation_identity_hmac, identity_key_version, source_id
                FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, merchant.id(), seed.paymentIntentId());
        assertThat(anchors).as("the seed must have real anchors to leak").isNotEmpty();
        for (Map<String, Object> anchor : anchors) {
            canaries.put("digest:" + anchor.get("relation_type"),
                    String.valueOf(anchor.get("relation_identity_hmac")));
        }
        canaries.put("email", IDENTITY.email());
        canaries.put("phone", IDENTITY.phone());
        canaries.put("pan", RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        canaries.put("customerId", seed.customerId().toString());
        canaries.put("paymentMethodId", seed.paymentMethodId().toString());
        canaries.forEach((name, value) -> assertThat(value).as(name).isNotBlank()
                .isNotEqualTo("null").hasSizeGreaterThan(5));

        for (String suffix : List.of("", "/timeline", "/related", "/webhook-activity")) {
            String body = mockMvc.perform(get("/risk/workspace/payments/"
                            + seed.paymentIntentId() + suffix)
                            .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                    operator.credential()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).as("route " + suffix).isNotBlank();
            canaries.forEach((name, value) ->
                    assertThat(body).as("route " + suffix + " leaks " + name).doesNotContain(value));
        }
    }

    @Test
    void theRelationshipIsPublishedAsAReasonCodeRatherThanAsTheMatchedValue() throws Exception {
        String body = mockMvc.perform(get("/risk/workspace/payments/"
                        + seed.paymentIntentId() + "/related")
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()))
                .andReturn().getResponse().getContentAsString();

        // The analyst learns that two payments share an email, never which email. That is what
        // keeps the pivot usable without turning the console into a customer data export.
        assertThat(body).contains("SAME_EMAIL");
        assertThat(body).doesNotContain("relationIdentityHmac", "identityKeyVersion",
                "relation_identity_hmac", "emailHash", "phoneHash", "cardFingerprint");
    }

    @Test
    void aDigestCannotBeReproducedFromThePlaintextAloneBecauseTheKeyIsSecret() throws Exception {
        String stored = jdbcTemplate.queryForObject("""
                SELECT relation_identity_hmac FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ? AND relation_type = 'SAME_EMAIL'
                """, String.class, merchant.id(), seed.paymentIntentId());
        assertThat(stored).isNotBlank();

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String unkeyed = HexFormat.of()
                .formatHex(sha256.digest(IDENTITY.email().getBytes(StandardCharsets.UTF_8)));
        // An unkeyed hash of an email address is trivially reversible by dictionary. The stored
        // value must not be one.
        assertThat(stored).isNotEqualTo(unkeyed);
        assertThat(stored).isEqualTo(
                correlationValueHasher.hmacSha256Lowercase("customer.email", IDENTITY.email()));
    }

    @Test
    void thesameValueUnderDifferentIdentityDomainsProducesUnrelatableDigests() {
        String value = "collide@example.com";
        String asEmail = correlationValueHasher.hmacSha256Lowercase("customer.email", value);
        String asPhone = correlationValueHasher.hmacSha256Lowercase("customer.phone", value);
        String asCard = correlationValueHasher.hmacSha256Lowercase("payment_method.card", value);

        // Without domain separation a phone number that happened to equal a card token would fuse
        // two unrelated people into one fraud ring.
        assertThat(List.of(asEmail, asPhone, asCard)).doesNotHaveDuplicates();
    }

    @Test
    void aDomainBoundaryCannotBeShiftedToForgeAMatch() {
        // The domain and the value are joined by an unambiguous separator. Without it,
        // hmac("customer.email" + "x@y") and hmac("customer.emailx" + "@y") would collide, letting
        // a caller who controls either half mint a digest that matches someone else's identity.
        String honest = correlationValueHasher.hmacSha256("customer.email", "x@y.example");
        String shifted = correlationValueHasher.hmacSha256("customer.emailx", "@y.example");
        assertThat(honest).isNotEqualTo(shifted);

        String emptyValue = correlationValueHasher.hmacSha256("customer.email", "");
        String emptyDomain = correlationValueHasher.hmacSha256("", "customer.email");
        assertThat(emptyValue).isNotEqualTo(emptyDomain);
    }

    @Test
    void everyStoredDigestIsFixedWidthSoItLeaksNothingAboutTheInputItCovers() {
        List<Map<String, Object>> anchors = jdbcTemplate.queryForList("""
                SELECT relation_type, relation_identity_hmac
                FROM payment_investigation_relation_anchors
                WHERE merchant_id = ?
                """, merchant.id());
        assertThat(anchors).isNotEmpty();

        // A short email and a long one must occupy the same width, or the digest column itself
        // becomes a side channel about the customer's data.
        for (Map<String, Object> anchor : anchors) {
            String digest = String.valueOf(anchor.get("relation_identity_hmac"));
            assertThat(digest).as(String.valueOf(anchor.get("relation_type")))
                    .hasSize(64).matches("[0-9a-f]{64}");
        }
    }
}
