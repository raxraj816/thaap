package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class PaymentIntentCreateContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String REQUEST_ID = "X-Request-Id";
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
    void createPaymentIntentPersistsNormalizedStateAndReturnsSafePublicResponse() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");

        var response = createPaymentIntent(merchant, customerId, "day6-create", """
                {
                  "customerId": "%s",
                  "amount": 1200,
                  "currency": "usd",
                  "captureMethod": "MANUAL",
                  "description": "Initial checkout authorization",
                  "metadata": {
                    "cart_id": "cart_123",
                    "order_id": "order_456"
                  }
                }
                """.formatted(customerId));

        var paymentIntentId = UUID.fromString(response.get("id").asText());
        assertThat(response.get("merchantId").asText()).isEqualTo(merchant.id().toString());
        assertThat(response.get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(response.get("amount").asLong()).isEqualTo(1200);
        assertThat(response.get("currency").asText()).isEqualTo("USD");
        assertThat(response.get("captureMethod").asText()).isEqualTo("MANUAL");
        assertThat(response.get("status").asText()).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(response.get("description").asText()).isEqualTo("Initial checkout authorization");
        assertThat(response.get("metadata").get("cart_id").asText()).isEqualTo("cart_123");
        assertThat(response.get("metadata").get("order_id").asText()).isEqualTo("order_456");
        assertThat(response.get("createdAt").asText()).isNotBlank();
        assertThat(response.get("updatedAt").asText()).isNotBlank();
        assertThat(response.has("requestHash")).isFalse();
        assertThat(response.has("responseBody")).isFalse();
        assertThat(response.has("apiKeyHash")).isFalse();

        var stored = jdbcTemplate.queryForMap("""
                SELECT merchant_id, customer_id, amount, currency, capture_method, status, description, metadata,
                       created_at, updated_at
                FROM payment_intents
                WHERE id = ?
                """, paymentIntentId);
        assertThat(stored.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(stored.get("CUSTOMER_ID")).isEqualTo(customerId);
        assertThat(stored.get("AMOUNT")).isEqualTo(1200L);
        assertThat(stored.get("CURRENCY")).isEqualTo("USD");
        assertThat(stored.get("CAPTURE_METHOD")).isEqualTo("MANUAL");
        assertThat(stored.get("STATUS")).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(stored.get("DESCRIPTION")).isEqualTo("Initial checkout authorization");
        assertThat(stored.get("METADATA").toString()).contains("\"cart_id\":\"cart_123\"");
        assertThat(stored.get("CREATED_AT")).isNotNull();
        assertThat(stored.get("UPDATED_AT")).isNotNull();

        assertThat(countRows("payment_intents")).isEqualTo(1);
        assertThat(countRows("webhook_deliveries")).isEqualTo(1);
        var delivery = jdbcTemplate.queryForMap("""
                SELECT merchant_id, event_type, resource_id, status, attempt_count
                FROM webhook_deliveries
                WHERE resource_id = ?
                """, paymentIntentId);
        assertThat(delivery.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(delivery.get("EVENT_TYPE")).isEqualTo("payment_intent.created");
        assertThat(delivery.get("STATUS")).isEqualTo("PENDING");
        assertThat(delivery.get("ATTEMPT_COUNT")).isEqualTo(0);
    }

    @Test
    void missingIdempotencyKeyUsesStandardErrorContract() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(REQUEST_ID, "req_day6_missing_idempotency")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1200,
                                  "currency": "USD"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_day6_missing_idempotency"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 400, "bad_request", "req_day6_missing_idempotency", "/payment_intents");
        assertThat(error.get("message").asText()).isEqualTo("Missing Idempotency-Key header");
        assertThat(countRows("payment_intents")).isZero();
    }

    @Test
    void idempotencyReplayReturnsOriginalResponseWithoutDuplicateIntentOrWebhookDelivery() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");
        var body = """
                {
                  "customerId": "%s",
                  "amount": 2300,
                  "currency": "USD"
                }
                """.formatted(customerId);

        var first = createPaymentIntent(merchant, customerId, "day6-replay", body);
        var replay = createPaymentIntent(merchant, customerId, "day6-replay", body);

        assertThat(replay).isEqualTo(first);
        assertThat(countRows("payment_intents")).isEqualTo(1);
        assertThat(countRows("webhook_deliveries")).isEqualTo(1);
        assertThat(countRows("idempotency_records")).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyReturnsConflict() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        createPaymentIntent(merchant, customerId, "day6-conflict", """
                {
                  "customerId": "%s",
                  "amount": 2300,
                  "currency": "USD"
                }
                """.formatted(customerId));

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "day6-conflict")
                        .header(REQUEST_ID, "req_day6_conflict")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 2301,
                                  "currency": "USD"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isConflict())
                .andExpect(header().string(REQUEST_ID, "req_day6_conflict"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 409, "conflict", "req_day6_conflict", "/payment_intents");
        assertThat(error.get("message").asText())
                .isEqualTo("Idempotency key was already used with a different request");
        assertThat(countRows("payment_intents")).isEqualTo(1);
    }

    @Test
    void validationErrorsUseStandardErrorContract() throws Exception {
        var merchant = createMerchant("merchant-a");

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "day6-validation")
                        .header(REQUEST_ID, "req_day6_validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 0,
                                  "currency": "US"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_day6_validation"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 400, "validation_failed", "req_day6_validation", "/payment_intents");
        assertThat(error.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(error.get("fieldErrors").findValuesAsText("field"))
                .contains("customerId", "amount", "currency");
        assertThat(countRows("payment_intents")).isZero();
    }

    @Test
    void unsupportedCurrencyIsRejectedBeforePersistence() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "unsupported-currency")
                        .header(REQUEST_ID, "req_unsupported_currency")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1200,
                                  "currency": "XYZ"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_unsupported_currency"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 400, "validation_failed", "req_unsupported_currency", "/payment_intents");
        assertThat(error.get("fieldErrors").findValuesAsText("field")).contains("currency");
        assertThat(countRows("payment_intents")).isZero();
    }

    @Test
    void excessiveAmountIsRejectedBeforePersistence() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "amount-too-large")
                        .header(REQUEST_ID, "req_amount_too_large")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 100000001,
                                  "currency": "USD"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_amount_too_large"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 400, "validation_failed", "req_amount_too_large", "/payment_intents");
        assertThat(error.get("fieldErrors").findValuesAsText("field")).contains("amount");
        assertThat(countRows("payment_intents")).isZero();
    }

    @Test
    void metadataShapeIsConstrained() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "bad-metadata")
                        .header(REQUEST_ID, "req_bad_metadata")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1200,
                                  "currency": "USD",
                                  "metadata": {
                                    "bad key": "not allowed"
                                  }
                                }
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_bad_metadata"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 400, "validation_failed", "req_bad_metadata", "/payment_intents");
        assertThat(error.get("fieldErrors").findValuesAsText("field"))
                .anySatisfy(field -> assertThat(field).contains("metadata"));
        assertThat(countRows("payment_intents")).isZero();
    }

    @Test
    void missingCaptureMethodDefaultsToAutomatic() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");

        var response = createPaymentIntent(merchant, customerId, "day6-default-capture", """
                {
                  "customerId": "%s",
                  "amount": 1200,
                  "currency": "USD"
                }
                """.formatted(customerId));

        assertThat(response.get("captureMethod").asText()).isEqualTo("AUTOMATIC");
        var storedCaptureMethod = jdbcTemplate.queryForObject("""
                SELECT capture_method
                FROM payment_intents
                WHERE id = ?
                """, String.class, UUID.fromString(response.get("id").asText()));
        assertThat(storedCaptureMethod).isEqualTo("AUTOMATIC");
    }

    @Test
    void merchantCannotCreatePaymentIntentForAnotherMerchantsCustomer() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        createWebhookEndpoint(merchantA, "https://merchant-a.example/webhooks");

        mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "day6-cross-merchant-customer")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1200,
                                  "currency": "USD"
                                }
                """.formatted(customerB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_intents")).isZero();
        assertThat(countRows("webhook_deliveries")).isZero();
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

    private JsonNode createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, String body)
            throws Exception {
        assertThat(body).contains(customerId.toString());
        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertStandardError(JsonNode error, int status, String code, String requestId, String path) {
        assertThat(error.get("requestId").asText()).isEqualTo(requestId);
        assertThat(error.get("timestamp").asText()).isNotBlank();
        assertThat(error.get("status").asInt()).isEqualTo(status);
        assertThat(error.get("error").asText()).isEqualTo(code);
        assertThat(error.get("path").asText()).isEqualTo(path);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
