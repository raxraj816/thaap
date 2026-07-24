package com.thaap.payments.velocity;

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
@SpringBootTest(properties = {
        "thaap.fraud.velocity.enabled=true",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=50ms",
        "spring.data.redis.timeout=50ms"
})
class VelocityDependencyUnavailableConfirmTests {

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
    void redisConnectionAmbiguityPersistsUnavailableEvidenceAndAppliesReviewFloor() throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"velocity_fraud_list-unavailable\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String apiKey = merchant.get("apiKey").asText();
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header("X-Api-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"unavailable@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", "unavailable-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4242424242424242"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", "unavailable-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":1200,"currency":"USD"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String confirmResponse = mockMvc.perform(post("/payment_intents/{id}/confirm",
                        UUID.fromString(intent.get("id").asText()))
                        .header("X-Api-Key", apiKey).header("Idempotency-Key", "unavailable-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + method.get("id").asText() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(confirmResponse);

        assertThat(response.get("status").asText()).isEqualTo("IN_REVIEW");
        UUID decisionId = UUID.fromString(response.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM fraud_decisions WHERE id = ?", String.class, decisionId)).isEqualTo("REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason_codes FROM fraud_decisions WHERE id = ?", String.class, decisionId))
                .contains("VELOCITY_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM velocity_observations
                WHERE fraud_decision_id = ? AND availability = 'UNAVAILABLE'
                  AND failure_cause = 'DEPENDENCY_FAILURE' AND other_attempt_count IS NULL
                """, Integer.class, decisionId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM authorizations", Integer.class)).isZero();
    }
}
