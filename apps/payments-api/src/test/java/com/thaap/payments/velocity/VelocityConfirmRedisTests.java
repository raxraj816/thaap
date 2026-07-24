package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.fraud.velocity.enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class VelocityConfirmRedisTests {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private StringRedisTemplate redisTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void fourthSameCardAndCustomerPaymentEntersReviewWithCompleteDurableVelocityLineage() throws Exception {
        Fixture fixture = createFixture();
        JsonNode fourth = null;
        for (int number = 1; number <= 4; number++) {
            UUID intentId = createIntent(fixture, number);
            fourth = confirm(fixture.apiKey(), intentId, fixture.paymentMethodId(), "velocity-confirm-" + number);
        }

        assertThat(fourth.get("status").asText()).isEqualTo("IN_REVIEW");
        UUID decisionId = UUID.fromString(fourth.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM fraud_decisions WHERE id = ?", String.class, decisionId)).isEqualTo("REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason_codes FROM fraud_decisions WHERE id = ?", String.class, decisionId))
                .contains("VELOCITY_THRESHOLD_MATCH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM velocity_observations", Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM velocity_observations
                WHERE fraud_decision_id IS NOT NULL AND availability = 'AVAILABLE'
                  AND script_version = 'velocity_v1' AND failure_cause IS NULL
                """, Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM velocity_observations
                WHERE fraud_decision_id = ? AND other_attempt_count = 3
                """, Integer.class, decisionId)).isEqualTo(3);
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT features_json FROM feature_snapshots WHERE fraud_decision_id = ?", String.class, decisionId);
        assertThat(snapshot).contains("\"velocity.card_attempts_10m\":3",
                "\"velocity.customer_attempts_1h\":3", "\"velocity.merchant_attempts_1m\":3")
                .doesNotContain("4242424242424242", fixture.customerId().toString(), fixture.merchantId().toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM authorizations", Integer.class)).isEqualTo(3);

        Set<String> redisKeys = redisTemplate.keys("*");
        assertThat(redisKeys).hasSize(15).allMatch(key -> key.matches(
                "thaap:velocity:v1:(?:[0-9a-f]{64}|owner:[0-9a-f]{64}:[0-9a-f]{64})"));
        assertThat(redisKeys.toString()).doesNotContain(
                "4242424242424242", fixture.customerId().toString(), fixture.merchantId().toString());
        assertThat(redisKeys.stream().filter(key -> key.matches("thaap:velocity:v1:[0-9a-f]{64}")))
                .hasSize(3).allSatisfy(key -> assertThat(redisTemplate.opsForZSet().size(key)).isEqualTo(4L));
    }

    private Fixture createFixture() throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"velocity_fraud_list-velocity\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String apiKey = merchant.get("apiKey").asText();
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header("X-Api-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"velocity-canary@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", "velocity-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4242424242424242"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Fixture(UUID.fromString(merchant.get("id").asText()), apiKey,
                UUID.fromString(customer.get("id").asText()), UUID.fromString(method.get("id").asText()));
    }

    private UUID createIntent(Fixture fixture, int number) throws Exception {
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header("X-Api-Key", fixture.apiKey()).header("Idempotency-Key", "velocity-intent-" + number)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":1200,"currency":"USD"}
                                """.formatted(fixture.customerId())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(intent.get("id").asText());
    }

    private JsonNode confirm(String apiKey, UUID intentId, UUID methodId, String key) throws Exception {
        String response = mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private record Fixture(UUID merchantId, String apiKey, UUID customerId, UUID paymentMethodId) {
    }
}
