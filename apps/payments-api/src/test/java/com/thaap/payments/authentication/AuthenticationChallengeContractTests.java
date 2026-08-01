package com.thaap.payments.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
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

@ActiveProfiles("test")
@SpringBootTest
class AuthenticationChallengeContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

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

    @Test
    void fraudChallengeSuccessContinuesSameAttemptAndAutomaticCaptureExactlyOnce() throws Exception {
        Fixture fixture = createChallenge("fraud-success", 100_000, "4999990000000001", "AUTOMATIC");
        assertThat(fixture.source()).isEqualTo("FRAUD_ENGINE");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();

        String body = completionBody("SUCCESS", fixture.clientSecret());
        String first = complete(fixture, "fraud-success-complete", body, 200);
        String replay = complete(fixture, "fraud-success-complete", body, 200);
        JsonNode response = objectMapper.readTree(first);

        assertThat(replay).isEqualTo(first);
        assertThat(response.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(response.get("resultCode").asText()).isEqualTo("AUTHENTICATED");
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo("CAPTURED");
        assertThat(response.get("paymentAttemptStatus").asText()).isEqualTo("AUTHORIZED");
        assertThat(value("SELECT action FROM fraud_decisions WHERE id = ?", fixture.decisionId()))
                .isEqualTo("REQUEST_3DS");
        assertThat(count("payment_attempts")).isOne();
        assertThat(count("fraud_decisions")).isOne();
        assertThat(count("authentication_challenges")).isOne();
        assertThat(count("authorizations")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(value("SELECT three_ds_result FROM authorizations")).isEqualTo("AUTHENTICATED");
        assertThat(value("SELECT processor_reference_id FROM authorizations"))
                .isEqualTo("auth_sim_" + fixture.attemptId().toString().replace("-", ""));
        java.util.List<java.util.Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT id, event_type, causation_event_id
                FROM domain_events
                WHERE stream_type = 'PAYMENT' AND stream_id = ?
                ORDER BY stream_version
                """, fixture.intentId());
        assertThat(events).extracting(event -> event.get("EVENT_TYPE")).containsExactly(
                "payment_intent.created", "fraud_decision.created",
                "authentication_challenge.created", "payment_attempt.completed",
                "authentication_challenge.completed", "authorization.created",
                "payment_attempt.completed", "capture.created");
        assertThat(events.get(5).get("CAUSATION_EVENT_ID")).isEqualTo(events.get(4).get("ID"));
        assertThat(events.get(6).get("CAUSATION_EVENT_ID")).isEqualTo(events.get(5).get("ID"));
        assertThat(events.get(7).get("CAUSATION_EVENT_ID")).isEqualTo(events.get(6).get("ID"));
    }

    @Test
    void processorChallengeSuccessCompletesExistingAuthorizationWithoutSecondChallenge() throws Exception {
        Fixture fixture = createChallenge("processor-success", 99_999, "4999990000000008", "MANUAL");
        assertThat(fixture.source()).isEqualTo("PROCESSOR");
        UUID pendingAuthorizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM authorizations", UUID.class);
        assertThat(value("SELECT status FROM authorizations")).isEqualTo("REQUIRES_ACTION");

        JsonNode response = objectMapper.readTree(complete(fixture, "processor-success-complete",
                completionBody("SUCCESS", fixture.clientSecret()), 200));

        assertThat(response.get("authorizationId").asText()).isEqualTo(pendingAuthorizationId.toString());
        assertThat(response.get("authorizationStatus").asText()).isEqualTo("APPROVED");
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo("REQUIRES_CAPTURE");
        assertThat(count("authorizations")).isOne();
        assertThat(count("authentication_challenges")).isOne();
        assertThat(count("ledger_entries")).isZero();
        assertThat(value("SELECT three_ds_result FROM authorizations")).isEqualTo("AUTHENTICATED");
        assertThat(value("SELECT processor_response_code FROM authorizations"))
                .isEqualTo("APPROVED_AFTER_AUTHENTICATION");
    }

    @Test
    void authenticatedContinuationMayDeclineWithoutCreatingMoneyOrAnotherDecision() throws Exception {
        Fixture fixture = createChallenge("authenticated-decline", 100_000, "4999990000000003", "AUTOMATIC");

        JsonNode response = objectMapper.readTree(complete(fixture, "authenticated-decline-complete",
                completionBody("SUCCESS", fixture.clientSecret()), 200));

        assertThat(response.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(response.get("authorizationStatus").asText()).isEqualTo("DECLINED");
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(response.get("paymentAttemptStatus").asText()).isEqualTo("DECLINED");
        assertThat(count("payment_attempts")).isOne();
        assertThat(count("fraud_decisions")).isOne();
        assertThat(count("authentication_challenges")).isOne();
        assertThat(count("authorizations")).isOne();
        assertThat(count("ledger_entries")).isZero();
        assertThat(value("SELECT processor_response_code FROM authorizations"))
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(value("SELECT three_ds_result FROM authorizations")).isEqualTo("AUTHENTICATED");
    }

    @Test
    void repeatedProcessorChallengeAfterAuthenticationFailsClosedAndRollsBack() throws Exception {
        Fixture fixture = createChallenge("second-loop", 99_999, "4999990000000009", "MANUAL");
        assertThat(fixture.source()).isEqualTo("PROCESSOR");

        complete(fixture, "second-loop-complete",
                completionBody("SUCCESS", fixture.clientSecret()), 409);

        assertThat(value("SELECT status FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .isEqualTo("PENDING");
        assertThat(value("SELECT status FROM payment_attempts WHERE id = ?", fixture.attemptId()))
                .isEqualTo("REQUIRES_ACTION");
        assertThat(value("SELECT status FROM authorizations")).isEqualTo("REQUIRES_ACTION");
        assertThat(count("fraud_decisions")).isOne();
        assertThat(count("authentication_challenges")).isOne();
        assertThat(count("authorizations")).isOne();
        assertThat(count("ledger_entries")).isZero();
    }

    @Test
    void failedAuthenticationCreatesNoAuthorizationOrMoneyAndTerminalReplayIsStable() throws Exception {
        Fixture fixture = createChallenge("failed", 100_000, "4999990000000001", "AUTOMATIC");

        JsonNode response = objectMapper.readTree(complete(fixture, "failed-complete",
                completionBody("FAILURE", fixture.clientSecret()), 200));
        JsonNode terminalReplay = objectMapper.readTree(complete(fixture, "failed-distinct-key",
                completionBody("SUCCESS", fixture.clientSecret()), 200));

        assertThat(response.get("status").asText()).isEqualTo("FAILED");
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(response.get("paymentAttemptStatus").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(terminalReplay).isEqualTo(response);
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
    }

    @Test
    void terminalChallengeStillRequiresTheTenantBoundSecret() throws Exception {
        Fixture fixture = createChallenge("terminal-secret", 100_000, "4999990000000001", "AUTOMATIC");
        String body = completionBody("FAILURE", fixture.clientSecret());
        String terminal = complete(fixture, "terminal-secret-complete", body, 200);

        String wrongSecret = "authc_" + "A".repeat(43);
        complete(fixture, "terminal-secret-wrong", completionBody("SUCCESS", wrongSecret), 404);
        String authenticatedReplay = complete(fixture, "terminal-secret-replay", body, 200);

        assertThat(authenticatedReplay).isEqualTo(terminal);
        assertThat(value("SELECT status FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .isEqualTo("FAILED");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
    }

    @Test
    void wrongSecretAndMerchantCancellationCannotLeaveAUsableChallenge() throws Exception {
        Fixture fixture = createChallenge("cancel", 100_000, "4999990000000001", "AUTOMATIC");
        String wrongSecret = "authc_" + "A".repeat(43);
        complete(fixture, "wrong-secret", completionBody("SUCCESS", wrongSecret), 404);
        assertThat(value("SELECT status FROM authentication_challenges")).isEqualTo("PENDING");

        mockMvc.perform(post("/payment_intents/{id}/cancel", fixture.intentId())
                        .header(API_KEY, fixture.apiKey())
                        .header(IDEMPOTENCY_KEY, "cancel-pending-authentication"))
                .andExpect(status().isOk());

        assertThat(value("SELECT status FROM authentication_challenges")).isEqualTo("CANCELED");
        assertThat(value("SELECT result_code FROM authentication_challenges")).isEqualTo("PAYMENT_CANCELED");
        assertThat(value("SELECT status FROM payment_attempts")).isEqualTo("CANCELED");
        assertThat(value("SELECT status FROM payment_intents")).isEqualTo("CANCELED");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
        complete(fixture, "canceled-completion", completionBody("SUCCESS", fixture.clientSecret()), 200);
        assertThat(value("SELECT status FROM authentication_challenges")).isEqualTo("CANCELED");
    }

    @Test
    void completionAtTheExactExpiryInstantFailsClosedWithoutProcessorOrMoneyTruth() throws Exception {
        Fixture fixture = createChallenge("expired", 100_000, "4999990000000001", "AUTOMATIC");
        jdbcTemplate.update("""
                UPDATE authentication_challenges
                SET created_at = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP),
                    expires_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, fixture.challengeId());

        JsonNode response = objectMapper.readTree(complete(fixture, "expired-complete",
                completionBody("SUCCESS", fixture.clientSecret()), 200));

        assertThat(response.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(response.get("resultCode").asText()).isEqualTo("CHALLENGE_EXPIRED");
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(response.get("paymentAttemptStatus").asText()).isEqualTo("AUTHENTICATION_EXPIRED");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
    }

    @Test
    void creationReplayIsExactButSecretIsEncryptedAtRestAndNeverReturnedByNormalAttemptReads()
            throws Exception {
        Fixture fixture = createChallenge("privacy", 100_000, "4999990000000001", "AUTOMATIC");
        UUID paymentMethodId = jdbcTemplate.queryForObject("SELECT id FROM payment_methods", UUID.class);

        JsonNode replay = objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm",
                        fixture.intentId())
                        .header(API_KEY, fixture.apiKey()).header(IDEMPOTENCY_KEY, "privacy-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(replay.get("nextAction").get("clientSecret").asText())
                .isEqualTo(fixture.clientSecret());
        String storedReplay = value("SELECT response_body FROM idempotency_records "
                + "WHERE idempotency_key = 'privacy-confirm'");
        assertThat(storedReplay).contains("authentication-replay-v1", "ciphertext", "nonce", "keyVersion");
        assertThat(storedReplay).doesNotContain(fixture.clientSecret());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM authentication_challenges").toString())
                .doesNotContain(fixture.clientSecret());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM audit_logs").toString())
                .doesNotContain(fixture.clientSecret());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM webhook_events").toString())
                .doesNotContain(fixture.clientSecret());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM idempotency_records").toString())
                .doesNotContain(fixture.clientSecret());
    }

    @Test
    void foreignTenantCannotCompleteChallengeEvenWithItsRawSecret() throws Exception {
        Fixture fixture = createChallenge("tenant-owner", 100_000, "4999990000000001", "AUTOMATIC");
        JsonNode foreignMerchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"authentication_challenge-foreign\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Fixture substituted = new Fixture(fixture.merchantId(), foreignMerchant.get("apiKey").asText(),
                fixture.intentId(), fixture.attemptId(), fixture.decisionId(), fixture.challengeId(),
                fixture.source(), fixture.clientSecret());

        complete(substituted, "foreign-tenant-complete",
                completionBody("SUCCESS", fixture.clientSecret()), 404);

        assertThat(value("SELECT status FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .isEqualTo("PENDING");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
    }

    private Fixture createChallenge(String name, long amount, String cardNumber, String captureMethod)
            throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"authentication_challenge-" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID merchantId = UUID.fromString(merchant.get("id").asText());
        String apiKey = merchant.get("apiKey").asText();
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + name + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"%s"}
                                """.formatted(customer.get("id").asText(), cardNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":%d,"currency":"USD","captureMethod":"%s"}
                                """.formatted(customer.get("id").asText(), amount, captureMethod)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode attempt = objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm",
                        intent.get("id").asText())
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + method.get("id").asText() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode nextAction = attempt.get("nextAction");
        assertThat(nextAction).isNotNull();
        assertThat(nextAction.get("clientSecret").asText()).startsWith("authc_");
        return new Fixture(merchantId, apiKey, UUID.fromString(intent.get("id").asText()),
                UUID.fromString(attempt.get("id").asText()),
                UUID.fromString(attempt.get("fraudDecisionId").asText()),
                UUID.fromString(nextAction.get("challengeId").asText()),
                nextAction.get("source").asText(), nextAction.get("clientSecret").asText());
    }

    private String complete(Fixture fixture, String key, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/payment_attempts/{attemptId}/authentication_challenges/{challengeId}/complete",
                        fixture.attemptId(), fixture.challengeId())
                        .header(API_KEY, fixture.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    private String completionBody(String outcome, String clientSecret) {
        return "{\"outcome\":\"" + outcome + "\",\"clientSecret\":\"" + clientSecret + "\"}";
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String value(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private record Fixture(UUID merchantId, String apiKey, UUID intentId, UUID attemptId,
            UUID decisionId, UUID challengeId, String source, String clientSecret) {
    }
}
