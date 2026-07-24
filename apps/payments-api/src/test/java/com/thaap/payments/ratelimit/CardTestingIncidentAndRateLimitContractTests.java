package com.thaap.payments.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.incident.CardTestingIncident;
import com.thaap.payments.incident.CardTestingIncidentService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
@SpringBootTest(properties = {
        "thaap.rate-limit.enabled=true", "thaap.rate-limit.window=30s",
        "thaap.rate-limit.confirm.merchant=3", "thaap.rate-limit.confirm.credential=100",
        "thaap.rate-limit.confirm.client-ip=100", "thaap.rate-limit.payment-method.merchant=3",
        "thaap.rate-limit.payment-method.credential=100", "thaap.rate-limit.payment-method.client-ip=100",
        "thaap.card-testing-incidents.enabled=true", "thaap.card-testing-incidents.minimum-attempts=3",
        "thaap.card-testing-incidents.minimum-declines=2",
        "thaap.card-testing-incidents.minimum-distinct-cards=2"
})
@Testcontainers(disabledWithoutDocker = true)
class CardTestingIncidentAndRateLimitContractTests {
    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

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
    @MockitoSpyBean private RedisRateLimitService redisRateLimitService;
    @MockitoSpyBean private CardTestingIncidentService incidentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        reset(redisRateLimitService);
        reset(incidentService);
        databaseCleaner.clean();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void persistedDeclinesOpenOneIncidentAndOnlyManagerResolvesWithoutPolicyMutation() throws Exception {
        Merchant merchant = merchant("card-testing-incident-incident");
        JsonNode customer = customer(merchant, "incident");
        List<String> cards = List.of("4999990000000002", "4999990000000003", "4999990000000004");
        for (int index = 0; index < cards.size(); index++) {
            JsonNode method = method(merchant, customer, cards.get(index), "incident-method-" + index);
            JsonNode intent = intent(merchant, customer, "incident-intent-" + index);
            JsonNode attempt = confirm(merchant, intent, method, "incident-confirm-" + index,
                    "203.0.113." + (index + 1), 201);
            assertThat(attempt.get("status").asText()).isEqualTo("DECLINED");
        }
        assertThat(count("card_testing_incidents")).isOne();
        assertThat(count("incident_evidence_links")).isEqualTo(3);
        Operator analyst = operator(merchant, "incident-analyst", "ANALYST");
        Operator manager = operator(merchant, "incident-manager", "RISK_MANAGER");
        JsonNode incidents = objectMapper.readTree(mockMvc.perform(get("/risk/incidents")
                        .header(RISK_KEY, manager.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode incident = incidents.get(0);
        assertThat(incident.get("sourceAttestation").asText())
                .isEqualTo("THAAP_PERSISTED_CONTROL_EVIDENCE");
        assertThat(incident.get("attemptCount").asInt()).isEqualTo(3);
        assertThat(incident.get("declineCount").asInt()).isEqualTo(3);
        assertThat(incident.get("distinctCardCount").asInt()).isEqualTo(3);
        assertThat(incident.get("distinctIpCount").asInt()).isEqualTo(3);
        String body = "{\"mitigationCode\":\"UPSTREAM_BLOCK_APPLIED\"}";
        mockMvc.perform(post("/risk/incidents/{id}/resolve", incident.get("id").asText())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-resolve")
                        .contentType(APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        String first = resolve(manager, incident.get("id").asText(), body, "manager-resolve", 200);
        String replay = resolve(manager, incident.get("id").asText(), body, "manager-resolve", 200);
        assertThat(replay).isEqualTo(first);
        assertThat(objectMapper.readTree(first).get("status").asText()).isEqualTo("RESOLVED");
        assertThat(count("card_testing_incident_resolutions")).isOne();
        assertThat(count("merchant_risk_policy_versions")).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM incident_evidence_links"))
                .hasMessageContaining("Card-testing incident history is append-only");
    }

    @Test
    void confirmLimitHasReplayNonConsumptionConflictAndBounded429() throws Exception {
        Merchant merchant = merchant("card-testing-incident-confirm-limit");
        JsonNode customer = customer(merchant, "confirm-limit");
        JsonNode method = method(merchant, customer, "4242424242424242", "confirm-limit-method");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        JsonNode firstIntent = intent(merchant, customer, "confirm-intent-1");
        String first = confirmRaw(merchant, firstIntent, method, "confirm-limit-1").getResponse().getContentAsString();
        String replay = confirmRaw(merchant, firstIntent, method, "confirm-limit-1").getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        for (int number = 2; number <= 3; number++) {
            confirm(merchant, intent(merchant, customer, "confirm-intent-" + number), method,
                    "confirm-limit-" + number, null, 201);
        }
        JsonNode fourth = intent(merchant, customer, "confirm-intent-4");
        MvcResult exceeded = confirmRaw(merchant, fourth, method, "confirm-limit-4");
        assertThat(exceeded.getResponse().getStatus()).isEqualTo(429);
        assertThat(exceeded.getResponse().getHeader("Retry-After")).matches("[1-9][0-9]*");
        assertThat(objectMapper.readTree(exceeded.getResponse().getContentAsString()).get("error").asText())
                .isEqualTo("rate_limit_exceeded");
        assertThat(confirmRaw(merchant, fourth, method, "confirm-limit-4").getResponse().getStatus()).isEqualTo(429);
        mockMvc.perform(post("/payment_intents/{id}/confirm", fourth.get("id").asText())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "confirm-limit-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + method.get("id").asText() + "\"}"))
                .andExpect(status().isConflict());
        Set<String> keys = redisTemplate.keys("thaap:rate-limit:v1:*");
        assertThat(keys).hasSize(3).allMatch(key -> key.matches("thaap:rate-limit:v1:[0-9a-f]{64}"));
        assertThat(keys).allSatisfy(key -> assertThat(redisTemplate.opsForHash().size(key)).isEqualTo(4L));
        assertThat(keys.toString()).doesNotContain("127.0.0.1", "confirm-limit", merchant.apiKey());
    }

    @Test
    void paymentMethodLimitAndDependencyAmbiguityHaveSeparate429And503Truth() throws Exception {
        Merchant merchant = merchant("card-testing-incident-method-limit");
        JsonNode customer = customer(merchant, "method-limit");
        for (int number = 1; number <= 3; number++) {
            method(merchant, customer, "42424242424242" + (10 + number), "method-limit-" + number);
        }
        MvcResult exceeded = methodResult(merchant, customer, "4242424242424299", "method-limit-4");
        assertThat(exceeded.getResponse().getStatus()).isEqualTo(429);
        assertThat(count("payment_methods")).isEqualTo(3);

        databaseCleaner.clean();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        Merchant outage = merchant("card-testing-incident-rate-outage");
        JsonNode outageCustomer = customer(outage, "rate-outage");
        doThrow(new RateLimitUnavailableException(RateLimitUnavailableException.Cause.DEPENDENCY_FAILURE,
                "injected ambiguity")).when(redisRateLimitService).evaluate(any(), any(), any(), anyInt());
        MvcResult unavailable = methodResult(outage, outageCustomer, "4242424242424242", "outage-method");
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(unavailable.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(objectMapper.readTree(unavailable.getResponse().getContentAsString()).get("error").asText())
                .isEqualTo("rate_limit_unavailable");
        assertThat(count("payment_methods")).isZero();
    }

    @Test
    void concurrentDetectorsSerializeToOneIncidentAndOneLinkPerDurableAttempt() throws Exception {
        Merchant merchant = merchant("card-testing-incident-concurrent-incident");
        JsonNode customer = customer(merchant, "concurrent-incident");
        doReturn(null).when(incidentService).detectFromPersistedEvidence(any(), any());
        for (int index = 0; index < 3; index++) {
            JsonNode method = method(merchant, customer, "499999000000000" + (index + 2),
                    "concurrent-method-" + index);
            JsonNode intent = intent(merchant, customer, "concurrent-intent-" + index);
            assertThat(confirm(merchant, intent, method, "concurrent-confirm-" + index,
                    "203.0.113." + (index + 20), 201).get("status").asText()).isEqualTo("DECLINED");
        }
        assertThat(count("card_testing_incidents")).isZero();
        reset(incidentService);
        MerchantContext merchantContext = new MerchantContext(merchant.id(), "concurrent", "test-prefix");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CardTestingIncident> first = executor.submit(() -> {
                start.await();
                return incidentService.detectFromPersistedEvidence(merchantContext, "concurrent-first");
            });
            Future<CardTestingIncident> second = executor.submit(() -> {
                start.await();
                return incidentService.detectFromPersistedEvidence(merchantContext, "concurrent-second");
            });
            start.countDown();
            assertThat(first.get().getId()).isEqualTo(second.get().getId());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("card_testing_incidents")).isOne();
        assertThat(count("incident_evidence_links")).isEqualTo(3);
    }

    private String resolve(Operator operator, String incidentId, String body, String key, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/risk/incidents/{id}/resolve", incidentId)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return result.getResponse().getContentAsString();
    }

    private MvcResult confirmRaw(Merchant merchant, JsonNode intent, JsonNode method, String key) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/confirm", intent.get("id").asText())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + method.get("id").asText() + "\"}"))
                .andReturn();
    }

    private JsonNode confirm(Merchant merchant, JsonNode intent, JsonNode method, String key,
            String ip, int expected) throws Exception {
        String context = ip == null ? "" : ",\"riskContext\":{\"ipAddress\":\"" + ip
                + "\",\"ipCountry\":\"US\"}";
        MvcResult result = mockMvc.perform(post("/payment_intents/{id}/confirm", intent.get("id").asText())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"paymentMethodId\":\""
                                + method.get("id").asText() + "\"" + context + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode intent(Merchant merchant, JsonNode customer, String key) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\""
                                + customer.get("id").asText()
                                + "\",\"amount\":1900,\"currency\":\"USD\",\"captureMethod\":\"MANUAL\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode method(Merchant merchant, JsonNode customer, String card, String key) throws Exception {
        MvcResult result = methodResult(merchant, customer, card, key);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult methodResult(Merchant merchant, JsonNode customer, String card, String key) throws Exception {
        return mockMvc.perform(post("/payment_methods").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer.get("id").asText()
                                + "\",\"cardNumber\":\"" + card + "\"}"))
                .andReturn();
    }

    private JsonNode customer(Merchant merchant, String key) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/customers").header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON).content("{\"email\":\"" + key + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private Merchant merchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Merchant(java.util.UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private Operator operator(Merchant merchant, String key, String role) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Operator(response.get("credential").asText());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Merchant(java.util.UUID id, String apiKey) {}
    private record Operator(String secret) {}
}
