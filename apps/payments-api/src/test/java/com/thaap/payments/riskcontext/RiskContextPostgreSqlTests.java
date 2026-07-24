package com.thaap.payments.riskcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RiskContextPostgreSqlTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DataSource dataSource;
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void databaseRejectsContextMutationDeletionAndIncompleteDirectInsert() throws Exception {
        Fixture fixture = createFixture("history");
        confirm(fixture, "history-confirm", fullContext(fixture, "203.0.113.81"), 201);
        UUID contextId = jdbcTemplate.queryForObject("SELECT id FROM risk_contexts", UUID.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE risk_contexts SET ip_country = 'GB' WHERE id = ?", contextId))
                .hasMessageContaining("risk context history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE risk_contexts SET fraud_decision_id = fraud_decision_id WHERE id = ?", contextId))
                .hasMessageContaining("risk context history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM risk_contexts WHERE id = ?", contextId))
                .hasMessageContaining("risk context history is immutable");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO risk_contexts
                    (id, merchant_id, payment_intent_id, payment_attempt_id, fraud_decision_id,
                     observed_at, traffic_origin, created_at)
                SELECT gen_random_uuid(), merchant_id, payment_intent_id, id, NULL,
                       CURRENT_TIMESTAMP, traffic_origin, CURRENT_TIMESTAMP
                  FROM payment_attempts WHERE id = ?
                """, fixture.attemptId()))
                .hasMessageContaining("uk_risk_contexts_attempt");
        assertThat(count("risk_contexts")).isOne();
    }

    @Test
    void contextConstraintFailureRollsBackWholeConfirmAndSameKeyRetriesCleanly() throws Exception {
        Fixture fixture = createFixture("rollback");
        jdbcTemplate.execute("ALTER TABLE risk_contexts ADD CONSTRAINT ck_test_risk_context_failure "
                + "CHECK (ip_country <> 'US') NOT VALID");
        try {
            confirm(fixture, "rollback-confirm", fullContext(fixture, "203.0.113.82"), 500);
        } finally {
            jdbcTemplate.execute("ALTER TABLE risk_contexts DROP CONSTRAINT ck_test_risk_context_failure");
        }

        assertThat(count("payment_attempts")).isZero();
        assertThat(count("risk_contexts")).isZero();
        assertThat(count("feature_snapshots")).isZero();
        assertThat(count("feature_values")).isZero();
        assertThat(count("fraud_decisions")).isZero();
        assertThat(count("authorizations")).isZero();
        assertThat(count("idempotency_records")).isEqualTo(2); // method + intent only

        confirm(fixture, "rollback-confirm", fullContext(fixture, "203.0.113.82"), 201);
        assertSingleContextLineage();
    }

    @Test
    void forcedDifferentContextDistinctKeysSerializeToExactlyOneWinningLineage() throws Exception {
        Fixture fixture = createFixture("distinct");
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
                Connection blocker = dataSource.getConnection();
                Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE feature_values IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> first = executor.submit(() -> performConfirm(
                    fixture, "distinct-a", fullContext(fixture, "203.0.113.83")));
            awaitBlockedQuery("feature_values", 1);
            Future<MvcResult> second = executor.submit(() -> performConfirm(
                    fixture, "distinct-b", fullContext(fixture, "203.0.113.84")));
            List<String> blocked = awaitBlockedQueries(2);
            assertThat(blocked).anyMatch(query -> query.toLowerCase().contains("feature_values"));
            assertThat(blocked).anyMatch(query -> query.toLowerCase().contains("payment_intents"));

            blocker.commit();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertSingleContextLineage();
        assertThat(jdbcTemplate.queryForObject("SELECT ip_identity_hmac FROM risk_contexts", String.class))
                .isNotBlank();
    }

    @Test
    void forcedSameKeyDifferentBodyWaitsAndCannotReplayAnotherContext() throws Exception {
        Fixture fixture = createFixture("same-key-conflict");
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
                Connection blocker = dataSource.getConnection();
                Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE feature_values IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> owner = executor.submit(() -> performConfirm(
                    fixture, "same-key", fullContext(fixture, "203.0.113.85")));
            awaitBlockedQuery("feature_values", 1);
            Future<MvcResult> conflicting = executor.submit(() -> performConfirm(
                    fixture, "same-key", fullContext(fixture, "203.0.113.86")));
            List<String> blocked = awaitBlockedQueries(2);
            assertThat(blocked).anyMatch(query -> query.toLowerCase().contains("idempotency_records"));

            blocker.commit();
            assertThat(owner.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(conflicting.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertSingleContextLineage();
    }

    private Fixture createFixture(String name) throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"risk_context-pg-" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID merchantId = UUID.fromString(merchant.get("id").asText());
        String apiKey = merchant.get("apiKey").asText();
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header("X-Api-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + name + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", name + "-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4999990000000001"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", name + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":1200,"currency":"USD","captureMethod":"MANUAL"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Fixture(merchantId, apiKey, UUID.fromString(intent.get("id").asText()),
                UUID.fromString(method.get("id").asText()), null);
    }

    private String fullContext(Fixture fixture, String ip) {
        return """
                {"paymentMethodId":"%s","riskContext":{"ipAddress":"%s","ipCountry":"US",
                "userAgent":"risk_context-private-canary","deviceId":"device-29","sessionId":"session-29",
                "shippingCountry":"CA","checkoutChannel":"WEB","customerAccountAgeDays":90,
                "loginAgeMinutes":3,"botScoreBps":200,"captchaOutcome":"PASSED"}}
                """.formatted(fixture.paymentMethodId(), ip);
    }

    private void confirm(Fixture fixture, String key, String body, int expectedStatus) throws Exception {
        MvcResult result = performConfirm(fixture, key, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        if (expectedStatus == 201) {
            fixture.attemptId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("id").asText());
        }
    }

    private MvcResult performConfirm(Fixture fixture, String key, String body) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/confirm", fixture.paymentIntentId())
                        .header("X-Api-Key", fixture.apiKey()).header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void assertSingleContextLineage() {
        assertThat(count("payment_attempts")).isOne();
        assertThat(count("risk_contexts")).isOne();
        assertThat(count("fraud_decisions")).isOne();
        assertThat(count("feature_snapshots")).isOne();
        assertThat(count("feature_values")).isEqualTo(18);
        assertThat(count("authorizations")).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT fraud_decision_id IS NOT NULL FROM risk_contexts",
                Boolean.class)).isTrue();
    }

    private List<String> awaitBlockedQueries(int expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        List<String> queries = List.of();
        while (Instant.now().isBefore(deadline)) {
            queries = jdbcTemplate.queryForList("""
                    SELECT query FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                    """, String.class);
            if (queries.size() >= expectedCount) {
                return queries;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Expected " + expectedCount + " blocked PostgreSQL backends but saw " + queries);
    }

    private void awaitBlockedQuery(String fragment, int expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query ILIKE ?
                    """, Integer.class, "%" + fragment + "%");
            if (count != null && count >= expectedCount) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Expected blocked PostgreSQL query containing " + fragment);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static final class Fixture {
        private final UUID merchantId;
        private final String apiKey;
        private final UUID paymentIntentId;
        private final UUID paymentMethodId;
        private UUID attemptId;

        private Fixture(UUID merchantId, String apiKey, UUID paymentIntentId, UUID paymentMethodId, UUID attemptId) {
            this.merchantId = merchantId;
            this.apiKey = apiKey;
            this.paymentIntentId = paymentIntentId;
            this.paymentMethodId = paymentMethodId;
            this.attemptId = attemptId;
        }

        String apiKey() { return apiKey; }
        UUID paymentIntentId() { return paymentIntentId; }
        UUID paymentMethodId() { return paymentMethodId; }
        UUID attemptId() { return attemptId; }
    }
}
