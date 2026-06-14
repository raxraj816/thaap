package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class PaymentIntentConfirmContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void confirmCreatesPaymentAttemptWithoutFraudOrProcessorSideEffects() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-a");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-a", 1200);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");

        var response = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-attempt-a");
        var paymentAttemptId = UUID.fromString(response.get("id").asText());

        assertThat(response.get("merchantId").asText()).isEqualTo(merchant.id().toString());
        assertThat(response.get("paymentIntentId").asText()).isEqualTo(paymentIntentId.toString());
        assertThat(response.get("paymentMethodId").asText()).isEqualTo(paymentMethodId.toString());
        assertThat(response.get("status").asText()).isEqualTo("CREATED");
        assertThat(response.get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(response.get("fraudDecisionId").isNull()).isTrue();
        assertThat(response.get("processorAuthorizationId").isNull()).isTrue();
        assertThat(response.get("threeDsStatus").isNull()).isTrue();

        var stored = jdbcTemplate.queryForMap("""
                SELECT merchant_id, payment_intent_id, payment_method_id, idempotency_key, status, attempt_number,
                       fraud_decision_id, processor_authorization_id, three_ds_status, created_at, updated_at
                FROM payment_attempts
                WHERE id = ?
                """, paymentAttemptId);
        assertThat(stored.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(stored.get("PAYMENT_INTENT_ID")).isEqualTo(paymentIntentId);
        assertThat(stored.get("PAYMENT_METHOD_ID")).isEqualTo(paymentMethodId);
        assertThat(stored.get("IDEMPOTENCY_KEY")).isEqualTo("confirm-attempt-a");
        assertThat(stored.get("STATUS")).isEqualTo("CREATED");
        assertThat(stored.get("ATTEMPT_NUMBER")).isEqualTo(1);
        assertThat(stored.get("FRAUD_DECISION_ID")).isNull();
        assertThat(stored.get("PROCESSOR_AUTHORIZATION_ID")).isNull();
        assertThat(stored.get("THREE_DS_STATUS")).isNull();
        assertThat(stored.get("CREATED_AT")).isNotNull();
        assertThat(stored.get("UPDATED_AT")).isNotNull();

        var fetched = getPaymentAttempt(merchant, paymentAttemptId);
        assertThat(fetched).isEqualTo(response);
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countWebhookDeliveries("payment_attempt.created")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isZero();
    }

    @Test
    void idempotentConfirmReplayDoesNotCreateDuplicateAttemptOrWebhookDelivery() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-replay");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-replay", 2300);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");

        var first = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-replay");
        var replay = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-replay");

        assertThat(replay).isEqualTo(first);
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countWebhookDeliveries("payment_attempt.created")).isEqualTo(1);
    }

    @Test
    void separateConfirmKeysCreateMonotonicAttemptNumbersForSamePaymentIntent() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-numbering");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-numbering", 3400);

        var first = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-number-1");
        var second = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-number-2");

        assertThat(first.get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(second.get("attemptNumber").asInt()).isEqualTo(2);
        assertThat(countRows("payment_attempts")).isEqualTo(2);
    }

    @Test
    void concurrentConfirmKeysCreateDistinctAttemptNumbersForSamePaymentIntent() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-concurrent");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-concurrent", 3450);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                    "confirm-concurrent-1"));
            var second = executor.submit(() -> confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                    "confirm-concurrent-2"));

            var firstResponse = first.get(5, TimeUnit.SECONDS);
            var secondResponse = second.get(5, TimeUnit.SECONDS);

            assertThat(List.of(firstResponse.get("attemptNumber").asInt(), secondResponse.get("attemptNumber").asInt()))
                    .containsExactlyInAnyOrder(1, 2);
            assertThat(countRows("payment_attempts")).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonConfirmablePaymentIntentRejectsAttemptCreation() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-invalid-status");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-invalid-status", 4500);
        cancelPaymentIntent(merchant, paymentIntentId, "cancel-before-confirm");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-invalid-status")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isConflict());
        assertThat(countRows("payment_attempts")).isZero();
    }

    @Test
    void merchantCannotConfirmAnotherMerchantsPaymentIntent() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentIntentA = createPaymentIntent(merchantA, customerA, "confirm-pi-cross-intent", 5600);
        var paymentMethodB = createPaymentMethod(merchantB, customerB, "confirm-pm-cross-intent");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentA)
                        .header(API_KEY, merchantB.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-cross-intent")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_attempts")).isZero();
    }

    @Test
    void merchantCannotConfirmWithAnotherMerchantsPaymentMethod() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentIntentA = createPaymentIntent(merchantA, customerA, "confirm-pi-cross-method", 6700);
        var paymentMethodB = createPaymentMethod(merchantB, customerB, "confirm-pm-cross-method");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentA)
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-cross-method")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_attempts")).isZero();
    }

    @Test
    void merchantCannotConfirmWithPaymentMethodForDifferentCustomer() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerA = createCustomer(merchant, "buyer-a@example.com");
        var customerB = createCustomer(merchant, "buyer-b@example.com");
        var paymentIntentA = createPaymentIntent(merchant, customerA, "confirm-pi-wrong-customer", 7800);
        var paymentMethodB = createPaymentMethod(merchant, customerB, "confirm-pm-wrong-customer");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentA)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-wrong-customer")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_attempts")).isZero();
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        return new MerchantAccess(UUID.fromString(json.get("id").asText()), json.get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        var response = mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey) throws Exception {
        var response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4242424242424242"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": %d,
                                  "currency": "USD"
                                }
                                """.formatted(customerId, amount)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private JsonNode confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        var response = mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getPaymentAttempt(MerchantAccess merchant, UUID paymentAttemptId) throws Exception {
        var response = mockMvc.perform(get("/payment_attempts/{id}", paymentAttemptId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void createWebhookEndpoint(MerchantAccess merchant, String url) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isCreated());
    }

    private void cancelPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().isOk());
    }

    private String confirmBody(UUID paymentMethodId) {
        return """
                {
                  "paymentMethodId": "%s"
                }
                """.formatted(paymentMethodId);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWebhookDeliveries(String eventType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = ?
                """, Integer.class, eventType);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
