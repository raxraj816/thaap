package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.fraud.velocity.enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class VelocityObservationPostgreSqlTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
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
    void productionDatabaseProtectsHistoryAndRejectsCrossTenantObservationLineage() throws Exception {
        Fixture first = createFixture("history-a");
        JsonNode firstResult = confirm(first, "history-a-confirm", 201);
        Fixture second = createFixture("history-b");
        confirm(second, "history-b-confirm", 201);
        UUID observationId = jdbcTemplate.queryForObject("""
                SELECT id FROM velocity_observations
                WHERE merchant_id = ? ORDER BY dimension LIMIT 1
                """, UUID.class, first.merchantId());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM velocity_observations
                WHERE merchant_id = ? AND fraud_decision_id = ? AND availability = 'AVAILABLE'
                """, Integer.class, first.merchantId(),
                UUID.fromString(firstResult.get("fraudDecisionId").asText()))).isEqualTo(3);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE velocity_observations SET other_attempt_count = 99 WHERE id = ?", observationId))
                .hasMessageContaining("velocity observation history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE velocity_observations SET fraud_decision_id = fraud_decision_id WHERE id = ?", observationId))
                .hasMessageContaining("velocity observation history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM velocity_observations WHERE id = ?", observationId))
                .hasMessageContaining("velocity observation history is immutable");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO velocity_observations
                    (id, merchant_id, payment_intent_id, payment_attempt_id, fraud_decision_id,
                     dimension, window_ms, availability, other_attempt_count, redis_time_ms,
                     observed_at, script_version, execution_mode, identity_key_version,
                     traffic_origin, created_at)
                SELECT gen_random_uuid(), ?, payment_intent_id, payment_attempt_id, NULL,
                       'IP_ATTEMPTS_10M', 600000, 'AVAILABLE', 0, 1720000000000,
                       to_timestamp(1720000000), 'velocity_v1', 'FIRST', 'test-v1',
                       traffic_origin, CURRENT_TIMESTAMP
                FROM velocity_observations WHERE merchant_id = ? LIMIT 1
                """, first.merchantId(), second.merchantId()))
                .hasMessageContaining("fk_velocity_observations_attempt");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feature_definitions", Integer.class)).isEqualTo(22);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                WHERE tgrelid = 'velocity_observations'::regclass AND NOT tgisinternal
                  AND tgname = 'trg_velocity_observations_immutable'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForList("SELECT * FROM velocity_observations").toString())
                .doesNotContain("4242424242424242", "history-a@example.com", "history-b@example.com");
    }

    @Test
    void databaseRollbackCompensatesRedisAndSameLogicalRetryStartsAtZero() throws Exception {
        Fixture fixture = createFixture("rollback");
        jdbcTemplate.execute("ALTER TABLE velocity_observations ADD CONSTRAINT ck_velocity_fraud_list_force_rollback "
                + "CHECK (availability <> 'AVAILABLE') NOT VALID");
        try {
            confirm(fixture, "rollback-confirm", 500);
        } finally {
            jdbcTemplate.execute("ALTER TABLE velocity_observations DROP CONSTRAINT ck_velocity_fraud_list_force_rollback");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_attempts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM velocity_observations", Integer.class)).isZero();
        assertThat(redisTemplate.keys("*")).isEmpty();

        JsonNode retried = confirm(fixture, "rollback-confirm", 201);
        UUID decisionId = UUID.fromString(retried.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM velocity_observations
                WHERE fraud_decision_id = ? AND other_attempt_count = 0 AND execution_mode = 'FIRST'
                """, Integer.class, decisionId)).isEqualTo(3);
        Set<String> keys = redisTemplate.keys("*");
        assertThat(keys).hasSize(6).allMatch(key -> key.matches(
                "thaap:velocity:v1:(?:[0-9a-f]{64}|owner:[0-9a-f]{64}:[0-9a-f]{64})"));
        assertThat(keys.stream().filter(key -> key.matches("thaap:velocity:v1:[0-9a-f]{64}")))
                .hasSize(3).allSatisfy(key -> assertThat(redisTemplate.opsForZSet().size(key)).isOne());
    }

    private Fixture createFixture(String name) throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"velocity_fraud_list-pg-" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String apiKey = merchant.get("apiKey").asText();
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header("X-Api-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + name + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", name + "-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4242424242424242"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", name + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":1200,"currency":"USD","captureMethod":"MANUAL"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Fixture(UUID.fromString(merchant.get("id").asText()), apiKey,
                UUID.fromString(intent.get("id").asText()), UUID.fromString(method.get("id").asText()));
    }

    private JsonNode confirm(Fixture fixture, String key, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/payment_intents/{id}/confirm", fixture.paymentIntentId())
                        .header("X-Api-Key", fixture.apiKey()).header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + fixture.paymentMethodId() + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString().isBlank()
                ? objectMapper.createObjectNode() : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Fixture(UUID merchantId, String apiKey, UUID paymentIntentId, UUID paymentMethodId) {
    }
}
