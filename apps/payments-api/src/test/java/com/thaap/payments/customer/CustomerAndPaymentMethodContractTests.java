package com.thaap.payments.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.security.SensitiveValueHasher;
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
class CustomerAndPaymentMethodContractTests {

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
    private SensitiveValueHasher sensitiveValueHasher;

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
    void customerCreationStoresKeyedPiiHashesWithoutReturningThem() throws Exception {
        var merchant = createMerchant("merchant-a");
        var response = mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Buyer@Example.COM",
                                  "phone": "+15555550123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        var customerId = UUID.fromString(json.get("id").asText());
        var expectedEmailHash = sensitiveValueHasher.hmacSha256Lowercase("customer.email", "Buyer@Example.COM");
        var expectedPhoneHash = sensitiveValueHasher.hmacSha256Lowercase("customer.phone", "+15555550123");

        assertThat(json.get("merchantId").asText()).isEqualTo(merchant.id().toString());
        assertThat(json.has("emailHash")).isFalse();
        assertThat(json.has("phoneHash")).isFalse();
        assertThat(response)
                .doesNotContain("Buyer@Example.COM")
                .doesNotContain("buyer@example.com")
                .doesNotContain("+15555550123");

        var stored = jdbcTemplate.queryForMap("""
                SELECT email_hash, phone_hash
                FROM customers
                WHERE id = ?
                """, customerId);
        assertThat(stored.get("EMAIL_HASH")).isEqualTo(expectedEmailHash);
        assertThat(stored.get("PHONE_HASH")).isEqualTo(expectedPhoneHash);
        assertThat(stored).doesNotContainValue("Buyer@Example.COM");
        assertThat(stored).doesNotContainValue("+15555550123");
    }

    @Test
    void customerValidationUsesStandardErrorContract() throws Exception {
        var merchant = createMerchant("merchant-a");
        var response = mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey())
                        .header(REQUEST_ID, "req_day5_customer_validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_day5_customer_validation"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertThat(error.get("requestId").asText()).isEqualTo("req_day5_customer_validation");
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.get("error").asText()).isEqualTo("validation_failed");
        assertThat(error.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(error.get("path").asText()).isEqualTo("/customers");
        assertThat(error.get("fieldErrors")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(error.get("fieldErrors").findValuesAsText("field")).contains("email");
    }

    @Test
    void paymentMethodCreationStoresInternalFingerprintWithoutReturningIt() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer@example.com");
        var response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "safe-card-metadata")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4242 4242 4242 4242"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        var paymentMethodId = UUID.fromString(json.get("id").asText());
        var expectedFingerprint = sensitiveValueHasher.hmacSha256("payment_method.card_fingerprint",
                "4242424242424242");

        assertThat(json.get("merchantId").asText()).isEqualTo(merchant.id().toString());
        assertThat(json.get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(json.get("type").asText()).isEqualTo("CARD");
        assertThat(json.get("token").asText()).startsWith("pm_tok_");
        assertThat(json.has("cardFingerprint")).isFalse();
        assertThat(json.get("cardBin").asText()).isEqualTo("424242");
        assertThat(json.get("cardLast4").asText()).isEqualTo("4242");
        assertThat(json.get("cardFunding").asText()).isEqualTo("CREDIT");
        assertThat(json.get("cardCountry").asText()).isEqualTo("US");
        assertThat(response)
                .doesNotContain("4242 4242 4242 4242")
                .doesNotContain("4242424242424242");

        var stored = jdbcTemplate.queryForMap("""
                SELECT token, card_fingerprint, card_bin, card_last4
                FROM payment_methods
                WHERE id = ?
                """, paymentMethodId);
        assertThat(stored.get("CARD_FINGERPRINT")).isEqualTo(expectedFingerprint);
        assertThat(stored.get("CARD_BIN")).isEqualTo("424242");
        assertThat(stored.get("CARD_LAST4")).isEqualTo("4242");
        assertThat(stored).doesNotContainValue("4242 4242 4242 4242");
        assertThat(stored).doesNotContainValue("4242424242424242");
    }

    @Test
    void paymentMethodValidationUsesStandardErrorContractForInvalidCardShape() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer@example.com");
        var response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "invalid-card-shape")
                        .header(REQUEST_ID, "req_day5_payment_method_validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4242x4242x4242"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_day5_payment_method_validation"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertThat(error.get("requestId").asText()).isEqualTo("req_day5_payment_method_validation");
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.get("error").asText()).isEqualTo("validation_failed");
        assertThat(error.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(error.get("path").asText()).isEqualTo("/payment_methods");
        assertThat(error.get("fieldErrors")).hasSize(1);
        assertThat(error.get("fieldErrors").get(0).get("field").asText()).isEqualTo("cardNumber");
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

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
