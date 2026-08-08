package com.thaap.payments.riskcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
import java.util.UUID;
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
class RiskContextContractTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private SensitiveValueHasher sensitiveValueHasher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void fullContextPersistsOnlyTenantBoundPurposeHmacsAndKeepsExistingAllowOutcome() throws Exception {
        Fixture fixture = createFixture("full");
        String ip = "203.0.113.71";
        String userAgent = "THAAP-PRIVATE-UA-CANARY/1";
        String device = "shared-correlation-canary-71";
        String session = device;
        String request = """
                {"paymentMethodId":"%s","riskContext":{"ipAddress":"%s","ipCountry":"US",
                "userAgent":"%s","deviceId":"%s","sessionId":"%s","shippingCountry":"CA",
                "checkoutChannel":"WEB","customerAccountAgeDays":120,"loginAgeMinutes":4,
                "botScoreBps":321,"captchaOutcome":"PASSED","observedAt":"%s"}}
                """.formatted(fixture.paymentMethodId(), ip, userAgent, device, session, DatabaseInstant.now());

        String first = confirm(fixture, "full-confirm", request, 201);
        String replay = confirm(fixture, "full-confirm", request, 201);
        JsonNode response = objectMapper.readTree(first);

        assertThat(replay).isEqualTo(first);
        assertThat(response.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_contexts", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT fraud_decision_id IS NOT NULL FROM risk_contexts",
                Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT identity_key_version FROM risk_contexts", String.class))
                .isEqualTo("test-v1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT device_identity_hmac <> session_identity_hmac FROM risk_contexts", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT ip_identity_hmac FROM risk_contexts", String.class))
                .isEqualTo(sensitiveValueHasher.hmacSha256("risk-context:ip:v1",
                        fixture.merchantId() + "\u0000" + ip));
        assertThat(jdbcTemplate.queryForObject("SELECT user_agent_identity_hmac FROM risk_contexts", String.class))
                .isEqualTo(sensitiveValueHasher.hmacSha256("risk-context:user-agent:v1",
                        fixture.merchantId() + "\u0000" + userAgent.toLowerCase()));
        assertThat(jdbcTemplate.queryForObject("SELECT device_identity_hmac FROM risk_contexts", String.class))
                .isEqualTo(sensitiveValueHasher.hmacSha256("risk-context:device:v1",
                        fixture.merchantId() + "\u0000" + device));
        assertThat(jdbcTemplate.queryForObject("SELECT session_identity_hmac FROM risk_contexts", String.class))
                .isEqualTo(sensitiveValueHasher.hmacSha256("risk-context:session:v1",
                        fixture.merchantId() + "\u0000" + session));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_values", Integer.class))
                .isEqualTo(18);
        String snapshot = jdbcTemplate.queryForObject("SELECT features_json FROM feature_snapshots", String.class);
        assertThat(snapshot).contains("\"risk.context_present\":true", "\"risk.ip_country\":\"US\"",
                        "\"risk.shipping_country\":\"CA\"", "\"risk.bot_score_bps\":321")
                .doesNotContain(ip, userAgent, device, session, "identity_hmac");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM risk_contexts").toString())
                .doesNotContain(ip, userAgent, device, session)
                .contains("ip_country=US", "shipping_country=CA", "checkout_channel=WEB");
        assertThat(jdbcTemplate.queryForList("SELECT * FROM audit_logs").toString())
                .doesNotContain(ip, userAgent, device, session);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM webhook_events").toString())
                .doesNotContain(ip, userAgent, device, session);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM idempotency_records").toString())
                .doesNotContain(ip, userAgent, device, session);
    }

    @Test
    void omittedContextPreservesLegacyConfirmAndCreatesNoSyntheticContext() throws Exception {
        Fixture fixture = createFixture("omitted");
        String response = confirm(fixture, "omitted-confirm",
                "{\"paymentMethodId\":\"" + fixture.paymentMethodId() + "\"}", 201);

        assertThat(objectMapper.readTree(response).get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_contexts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_values", Integer.class)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject("SELECT features_json FROM feature_snapshots", String.class))
                .contains("\"risk.context_present\":false")
                .doesNotContain("risk.ip_country", "risk.shipping_country");
    }

    @Test
    void unknownForbiddenAndFarFutureContextFailsBeforeCreatingPaymentTruth() throws Exception {
        Fixture fixture = createFixture("invalid");
        String future = DatabaseInstant.now().plusSeconds(3600).toString();
        String request = """
                {"paymentMethodId":"%s","riskContext":{"ipAddress":"203.0.113.9",
                "observedAt":"%s","email":"forbidden@example.com"}}
                """.formatted(fixture.paymentMethodId(), future);

        confirm(fixture, "invalid-confirm", request, 400);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_attempts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_contexts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_decisions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authorizations", Integer.class)).isZero();
    }

    @Test
    void partialIpv6ContextPersistsOnlyPresentFeaturesWithoutFabricatingMissingValues() throws Exception {
        Fixture fixture = createFixture("partial-ipv6");
        String request = """
                {"paymentMethodId":"%s","riskContext":{"ipAddress":"2001:db8::71",
                "checkoutChannel":"API","captchaOutcome":"UNAVAILABLE"}}
                """.formatted(fixture.paymentMethodId());

        confirm(fixture, "partial-ipv6-confirm", request, 201);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_contexts", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT ip_identity_hmac FROM risk_contexts", String.class))
                .matches("[0-9a-f]{64}");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_values", Integer.class)).isEqualTo(11);
        String snapshot = jdbcTemplate.queryForObject("SELECT features_json FROM feature_snapshots", String.class);
        assertThat(snapshot).contains("\"risk.context_present\":true", "\"risk.checkout_channel\":\"API\"",
                        "\"risk.captcha_outcome\":\"UNAVAILABLE\"")
                .doesNotContain("risk.ip_country", "risk.bot_score_bps", "2001:db8");
    }

    @ParameterizedTest(name = "rejects RiskContext grammar partition {0}")
    @MethodSource("invalidRiskContexts")
    void invalidGrammarPartitionsFailBeforeAnyConfirmTruth(String name, String riskContext) throws Exception {
        Fixture fixture = createFixture("grammar-" + name);
        String request = "{\"paymentMethodId\":\"" + fixture.paymentMethodId()
                + "\",\"riskContext\":" + riskContext + "}";

        confirm(fixture, "grammar-" + name, request, 400);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_attempts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_contexts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_decisions", Integer.class)).isZero();
    }

    @Test
    void contextPresencePreservesAllowBlockStepUpAndReviewDecisionOutcomes() throws Exception {
        assertSingleAttemptParity("allow", 1_200L, "4999990000000001", "ALLOW", "AUTHORIZED");
        assertSingleAttemptParity("block", 5_000_000L, "4999990000000001", "BLOCK", "BLOCKED");
        assertSingleAttemptParity("step-up", 100_000L, "4999990000000001",
                "REQUEST_3DS", "REQUIRES_ACTION");

        Fixture without = createFixture("review-without", 99_999L, "4999990000000003");
        Fixture with = createFixture("review-with", 99_999L, "4999990000000003");
        JsonNode withoutResult = null;
        JsonNode withResult = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            withoutResult = objectMapper.readTree(confirm(without, "review-without-" + attempt,
                    "{\"paymentMethodId\":\"" + without.paymentMethodId() + "\"}", 201));
            withResult = objectMapper.readTree(confirm(with, "review-with-" + attempt,
                    contextBody(with, "203.0.113." + (90 + attempt)), 201));
        }
        assertThat(withoutResult.get("status").asText()).isEqualTo("IN_REVIEW");
        assertThat(withResult.get("status").asText()).isEqualTo(withoutResult.get("status").asText());
        assertThat(action(withResult)).isEqualTo(action(withoutResult)).isEqualTo("REVIEW");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT ip_identity_hmac) = COUNT(*)
                FROM risk_contexts WHERE ip_identity_hmac IS NOT NULL
                """, Boolean.class)).isTrue();
    }

    static java.util.stream.Stream<Arguments> invalidRiskContexts() {
        return java.util.stream.Stream.of(
                Arguments.of("bad-ipv4", "{\"ipAddress\":\"256.1.1.1\"}"),
                Arguments.of("hostname", "{\"ipAddress\":\"localhost\"}"),
                Arguments.of("country-case", "{\"ipCountry\":\"us\"}"),
                Arguments.of("country-length", "{\"shippingCountry\":\"USA\"}"),
                Arguments.of("channel", "{\"checkoutChannel\":\"KIOSK\"}"),
                Arguments.of("negative-age", "{\"customerAccountAgeDays\":-1}"),
                Arguments.of("age-overflow", "{\"customerAccountAgeDays\":36501}"),
                Arguments.of("fractional-login", "{\"loginAgeMinutes\":1.5}"),
                Arguments.of("bot-overflow", "{\"botScoreBps\":10001}"),
                Arguments.of("captcha", "{\"captchaOutcome\":\"UNKNOWN\"}"),
                Arguments.of("control-character", "{\"userAgent\":\"private\\u0001canary\"}"),
                Arguments.of("forbidden-email", "{\"email\":\"private@example.com\"}"),
                Arguments.of("nested-unknown", "{\"device\":{\"id\":\"private\"}}"),
                Arguments.of("device-grammar", "{\"deviceId\":\"contains space\"}"),
                Arguments.of("future-time", "{\"observedAt\":\"2999-01-01T00:00:00Z\"}"));
    }

    private void assertSingleAttemptParity(String name, long amount, String cardNumber,
            String expectedAction, String expectedStatus) throws Exception {
        Fixture without = createFixture(name + "-without", amount, cardNumber);
        Fixture with = createFixture(name + "-with", amount, cardNumber);
        JsonNode withoutResult = objectMapper.readTree(confirm(without, name + "-without-confirm",
                "{\"paymentMethodId\":\"" + without.paymentMethodId() + "\"}", 201));
        JsonNode withResult = objectMapper.readTree(confirm(with, name + "-with-confirm",
                contextBody(with, "203.0.113.89"), 201));

        assertThat(withoutResult.get("status").asText()).isEqualTo(expectedStatus);
        assertThat(withResult.get("status").asText()).isEqualTo(withoutResult.get("status").asText());
        assertThat(action(withoutResult)).isEqualTo(expectedAction);
        assertThat(action(withResult)).isEqualTo(expectedAction);
    }

    private String action(JsonNode attemptResponse) {
        UUID decisionId = UUID.fromString(attemptResponse.get("fraudDecisionId").asText());
        return jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, decisionId);
    }

    private String contextBody(Fixture fixture, String ip) {
        return """
                {"paymentMethodId":"%s","riskContext":{"ipAddress":"%s","ipCountry":"US",
                "shippingCountry":"CA","checkoutChannel":"WEB","botScoreBps":400,
                "captchaOutcome":"PASSED"}}
                """.formatted(fixture.paymentMethodId(), ip);
    }

    private Fixture createFixture(String name) throws Exception {
        return createFixture(name, 1_200L, "4999990000000001");
    }

    private Fixture createFixture(String name, long amount, String cardNumber) throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"risk_context-" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String apiKey = merchant.get("apiKey").asText();
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header("X-Api-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + name + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", name + "-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4999990000000001"}
                                """.formatted(customer.get("id").asText()).replace(
                                        "4999990000000001", cardNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", name + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":%d,"currency":"USD"}
                                """.formatted(customer.get("id").asText(), amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Fixture(UUID.fromString(merchant.get("id").asText()), apiKey,
                UUID.fromString(intent.get("id").asText()),
                UUID.fromString(method.get("id").asText()));
    }

    private String confirm(Fixture fixture, String key, String body, int statusCode) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/confirm", fixture.paymentIntentId())
                        .header("X-Api-Key", fixture.apiKey()).header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
    }

    private record Fixture(UUID merchantId, String apiKey, UUID paymentIntentId, UUID paymentMethodId) {
    }
}
