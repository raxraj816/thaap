package com.thaap.payments.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.security.SecretHasher;
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
class MerchantAdminContractTests {

    private static final String API_KEY = "X-Api-Key";
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
    void merchantCreationRevealsRawApiKeyOnceAndPersistsOnlyPrefixAndHash() throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Demo Merchant"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        var merchantId = UUID.fromString(json.get("id").asText());
        var apiKey = json.get("apiKey").asText();
        var apiKeyPrefix = json.get("apiKeyPrefix").asText();

        assertThat(json.get("name").asText()).isEqualTo("Demo Merchant");
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.has("apiKeyHash")).isFalse();
        assertThat(apiKey).startsWith("thaap_test_");
        assertThat(apiKey).startsWith(apiKeyPrefix);
        assertThat(apiKeyPrefix).isEqualTo(SecretHasher.prefix(apiKey));

        var stored = jdbcTemplate.queryForMap("""
                SELECT api_key_prefix, api_key_hash
                FROM merchants
                WHERE id = ?
                """, merchantId);
        assertThat(stored.get("API_KEY_PREFIX")).isEqualTo(apiKeyPrefix);
        assertThat(stored.get("API_KEY_HASH")).isEqualTo(SecretHasher.sha256(apiKey));
        assertThat(stored).doesNotContainValue(apiKey);

        var customerResponse = mockMvc.perform(post("/customers")
                        .header(API_KEY, apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var customer = objectMapper.readTree(customerResponse);

        assertThat(customer.get("merchantId").asText()).isEqualTo(merchantId.toString());
    }

    @Test
    void merchantCreationRequiresBootstrapToken() throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(REQUEST_ID, "req_day5_merchant_bootstrap")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Demo Merchant"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(header().string(REQUEST_ID, "req_day5_merchant_bootstrap"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertThat(error.get("requestId").asText()).isEqualTo("req_day5_merchant_bootstrap");
        assertThat(error.get("status").asInt()).isEqualTo(403);
        assertThat(error.get("error").asText()).isEqualTo("client_error");
        assertThat(error.get("message").asText()).isEqualTo("Invalid merchant admin bootstrap token");
        assertThat(error.get("path").asText()).isEqualTo("/admin/merchants");
    }

    @Test
    void merchantCreationValidationUsesStandardErrorContract() throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(REQUEST_ID, "req_day5_merchant_validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_day5_merchant_validation"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertThat(error.get("requestId").asText()).isEqualTo("req_day5_merchant_validation");
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.get("error").asText()).isEqualTo("validation_failed");
        assertThat(error.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(error.get("path").asText()).isEqualTo("/admin/merchants");
        assertThat(error.get("fieldErrors")).hasSize(1);
        assertThat(error.get("fieldErrors").get(0).get("field").asText()).isEqualTo("name");
    }
}
