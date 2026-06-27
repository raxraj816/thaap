package com.thaap.payments.paymentattempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentAttemptAuthorizationContractTests {

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
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void sameMerchantCanReadSafeAuthorizationTruthForPaymentAttempt() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "authorization-reader@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "auth-read-pm", "4999990000000002");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "auth-read-pi");
        JsonNode paymentAttempt = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "auth-read-confirm");
        UUID paymentAttemptId = UUID.fromString(paymentAttempt.get("id").asText());

        String responseBody = mockMvc.perform(get("/payment_attempts/{id}/authorization", paymentAttemptId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode response = objectMapper.readTree(responseBody);

        assertThat(response.get("paymentAttemptId").asText()).isEqualTo(paymentAttemptId.toString());
        assertThat(response.get("processor").asText()).isEqualTo("THAAP_SIMULATOR");
        assertThat(response.get("processorReferenceId").asText())
                .isEqualTo(paymentAttempt.get("processorAuthorizationId").asText());
        assertThat(response.get("processorResponseCode").asText()).isEqualTo("ISSUER_DECLINE");
        assertThat(response.get("networkStatus").asText()).isEqualTo("DECLINED");
        assertThat(response.get("requestedAmount").asLong()).isEqualTo(1200L);
        assertThat(response.get("authorizedAmount").asLong()).isZero();
        assertThat(response.get("currency").asText()).isEqualTo("USD");
        assertThat(response.get("status").asText()).isEqualTo("DECLINED");
        assertThat(response.get("cvcCheck").asText()).isEqualTo("PASS");
        assertThat(response.get("avsLine1Check").asText()).isEqualTo("PASS");
        assertThat(response.get("avsPostalCodeCheck").asText()).isEqualTo("PASS");
        assertThat(response.get("threeDsResult").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(response.get("createdAt").asText()).isNotBlank();
        assertThat(response.has("simulatorOutcomeCode")).isFalse();
    }

    @Test
    void anotherMerchantCannotReadAuthorizationForPaymentAttempt() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        MerchantAccess otherMerchant = createMerchant("merchant-b");
        UUID customerId = createCustomer(merchant, "authorization-owner@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "auth-cross-pm", "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "auth-cross-pi");
        JsonNode paymentAttempt = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                "auth-cross-confirm");

        mockMvc.perform(get("/payment_attempts/{id}/authorization", paymentAttempt.get("id").asText())
                        .header(API_KEY, otherMerchant.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void authorizationReadResponseDoesNotExposeSensitivePaymentOrTenantInputs() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "sensitive-reader@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "auth-safe-pm", "4999990000000008");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "auth-safe-pi");
        JsonNode paymentAttempt = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "auth-safe-confirm");

        String responseBody = mockMvc.perform(get("/payment_attempts/{id}/authorization", paymentAttempt.get("id").asText())
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .doesNotContain("4999990000000008")
                .doesNotContain("sensitive-reader@example.com")
                .doesNotContain(merchant.apiKey())
                .doesNotContain("auth-safe-confirm")
                .doesNotContain("fingerprint")
                .doesNotContain("apiKey")
                .doesNotContain("hash");
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        String response = mockMvc.perform(post("/admin/merchants")
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
        JsonNode json = objectMapper.readTree(response);
        return new MerchantAccess(UUID.fromString(json.get("id").asText()), json.get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        String response = mockMvc.perform(post("/customers")
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

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey,
            String cardNumber) throws Exception {
        String response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "%s"
                                }
                                """.formatted(customerId, cardNumber)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1200,
                                  "currency": "USD",
                                  "captureMethod": "MANUAL"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private JsonNode confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
