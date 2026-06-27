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
class PaymentIntentLifecycleContractTests {

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
    void cancelIsAllowedFromRequiresConfirmationAndReturnsReconciliationTimestamps() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentIntent = createPaymentIntent(merchant, customerId, "cancel-state", 1200);

        var response = mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntent)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "cancel-state-mutation"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);

        assertThat(json.get("status").asText()).isEqualTo("CANCELED");
        assertThat(json.get("createdAt").asText()).isNotBlank();
        assertThat(json.get("updatedAt").asText()).isNotBlank();
        assertThat(statusFor(paymentIntent)).isEqualTo("CANCELED");
    }

    @Test
    void captureBeforeAuthorizationIsRejectedWithoutMutatingState() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentIntent = createPaymentIntent(merchant, customerId, "capture-invalid-state", 2300);

        var response = mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntent)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-invalid-state-mutation")
                        .header(REQUEST_ID, "req_capture_invalid_state"))
                .andExpect(status().isConflict())
                .andExpect(header().string(REQUEST_ID, "req_capture_invalid_state"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 409, "conflict", "req_capture_invalid_state",
                "/payment_intents/" + paymentIntent + "/capture");
        assertThat(error.get("message").asText())
                .isEqualTo("PaymentIntent cannot be captured from status REQUIRES_CONFIRMATION");
        assertThat(statusFor(paymentIntent)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @Test
    void cancelAfterCancelIsRejectedWithoutDuplicateWebhookDelivery() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentIntent = createPaymentIntent(merchant, customerId, "cancel-twice", 3400);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");
        cancelPaymentIntent(merchant, paymentIntent, "first-cancel");

        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntent)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "second-cancel"))
                .andExpect(status().isConflict());

        assertThat(statusFor(paymentIntent)).isEqualTo("CANCELED");
        assertThat(countWebhookDeliveries("payment_intent.canceled")).isEqualTo(1);
    }

    @Test
    void refundBeforeCaptureIsRejectedWithoutMutatingState() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentIntent = createPaymentIntent(merchant, customerId, "refund-invalid-state", 4500);

        var response = mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "refund-invalid-state-mutation")
                        .header(REQUEST_ID, "req_refund_invalid_state")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 500
                                }
                                """.formatted(paymentIntent)))
                .andExpect(status().isConflict())
                .andExpect(header().string(REQUEST_ID, "req_refund_invalid_state"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 409, "conflict", "req_refund_invalid_state", "/refunds");
        assertThat(error.get("message").asText())
                .isEqualTo("PaymentIntent cannot be refunded from status REQUIRES_CONFIRMATION");
        assertThat(statusFor(paymentIntent)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @Test
    void capturedStateWithoutAuthorizationAndLedgerEvidenceCannotBeRefunded() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentIntent = createPaymentIntent(merchant, customerId, "refund-captured", 4500);
        markCaptured(paymentIntent);

        var response = mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "refund-captured-full")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 4500
                                }
                                """.formatted(paymentIntent)))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);

        assertThat(json.get("message").asText())
                .isEqualTo("PaymentIntent does not have approved authorization evidence for Refund");
        assertThat(statusFor(paymentIntent)).isEqualTo("CAPTURED");
    }

    @Test
    void partialRefundIsRejectedUntilRefundAccountingExists() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentIntent = createPaymentIntent(merchant, customerId, "refund-partial", 4500);
        markCaptured(paymentIntent);

        var response = mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "refund-partial-mutation")
                        .header(REQUEST_ID, "req_refund_partial")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 500
                                }
                                """.formatted(paymentIntent)))
                .andExpect(status().isConflict())
                .andExpect(header().string(REQUEST_ID, "req_refund_partial"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertStandardError(error, 409, "conflict", "req_refund_partial", "/refunds");
        assertThat(error.get("message").asText())
                .isEqualTo("Refund amount must equal captured PaymentIntent amount until partial refunds are supported");
        assertThat(statusFor(paymentIntent)).isEqualTo("CAPTURED");
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

    private void cancelPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().isOk());
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

    private String statusFor(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
    }

    private void markCaptured(UUID paymentIntentId) {
        jdbcTemplate.update("""
                UPDATE payment_intents
                SET status = 'CAPTURED'
                WHERE id = ?
                """, paymentIntentId);
    }

    private int countWebhookDeliveries(String eventType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = ?
                """, Integer.class, eventType);
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
