package com.thaap.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.idempotency.IdempotencyRepository;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.review.ReviewCaseRepository;
import com.thaap.payments.rules.FraudRuleRepository;
import com.thaap.payments.webhook.WebhookDeliveryRepository;
import com.thaap.payments.webhook.WebhookEndpointRepository;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
@Import(ApiErrorContractTests.ErrorEndpointConfiguration.class)
class ApiErrorContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String REQUEST_ID = "X-Request-Id";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private WebhookEndpointRepository webhookEndpointRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private FraudDecisionRepository fraudDecisionRepository;

    @Autowired
    private FraudRuleRepository fraudRuleRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        webhookDeliveryRepository.deleteAll();
        webhookEndpointRepository.deleteAll();
        idempotencyRepository.deleteAll();
        paymentMethodRepository.deleteAll();
        paymentIntentRepository.deleteAll();
        customerRepository.deleteAll();
        fraudDecisionRepository.deleteAll();
        fraudRuleRepository.deleteAll();
        reviewCaseRepository.deleteAll();
        merchantRepository.deleteAll();
    }

    @Test
    void providedRequestIdIsReturnedAndIncludedInErrorBody() throws Exception {
        var response = mockMvc.perform(post("/customers")
                        .header(REQUEST_ID, "req_day3_contract")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(REQUEST_ID, "req_day3_contract"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 401, "unauthorized", "req_day3_contract", "/customers");
        assertThat(error.get("message").asText()).isEqualTo("Missing X-Api-Key header");
    }

    @Test
    void missingRequestIdIsGeneratedAndPropagated() throws Exception {
        var mvcResult = mockMvc.perform(post("/customers")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(REQUEST_ID))
                .andReturn();

        var generatedRequestId = mvcResult.getResponse().getHeader(REQUEST_ID);
        var error = objectMapper.readTree(mvcResult.getResponse().getContentAsString());

        assertThat(generatedRequestId).isNotBlank();
        assertThat(UUID.fromString(generatedRequestId)).isNotNull();
        assertStandardError(error, 401, "unauthorized", generatedRequestId, "/customers");
    }

    @Test
    void errorHandlerGeneratesFallbackRequestIdWhenFilterDidNotRun() throws Exception {
        var mockMvcWithoutRequestIdFilter = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        var mvcResult = mockMvcWithoutRequestIdFilter.perform(get("/test-errors/response-status"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(REQUEST_ID))
                .andReturn();

        var generatedRequestId = mvcResult.getResponse().getHeader(REQUEST_ID);
        var error = objectMapper.readTree(mvcResult.getResponse().getContentAsString());

        assertThat(generatedRequestId).isNotBlank();
        assertThat(UUID.fromString(generatedRequestId)).isNotNull();
        assertStandardError(error, 404, "not_found", generatedRequestId, "/test-errors/response-status");
    }

    @Test
    void requestBodyValidationErrorsUseStandardShape() throws Exception {
        var merchant = createMerchant("merchant-a");

        var response = mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey())
                        .header(REQUEST_ID, "req_validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_validation"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 400, "validation_failed", "req_validation", "/customers");
        assertThat(error.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(error.get("fieldErrors")).hasSize(1);
        assertThat(error.get("fieldErrors").get(0).get("field").asText()).isEqualTo("email");
        assertThat(error.get("fieldErrors").get(0).get("message").asText()).isNotBlank();
    }

    @Test
    void typeMismatchPathParameterErrorsUseStandardShape() throws Exception {
        var merchant = createMerchant("merchant-a");

        var response = mockMvc.perform(get("/payment_intents/not-a-uuid")
                        .header(API_KEY, merchant.apiKey())
                        .header(REQUEST_ID, "req_bad_parameter"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 400, "bad_request", "req_bad_parameter", "/payment_intents/not-a-uuid");
        assertThat(error.get("message").asText()).isEqualTo("Invalid id parameter");
    }

    @Test
    void parameterValidationErrorsUseParameterNames() throws Exception {
        var response = mockMvc.perform(get("/test-errors/parameter-validation")
                        .param("amount", "0")
                        .header(REQUEST_ID, "req_parameter_validation"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 400, "validation_failed", "req_parameter_validation",
                "/test-errors/parameter-validation");
        assertThat(error.get("message").asText()).isEqualTo("Request parameter validation failed");
        assertThat(error.get("fieldErrors")).hasSize(1);
        assertThat(error.get("fieldErrors").get(0).get("field").asText()).isEqualTo("amount");
        assertThat(error.get("fieldErrors").get(0).get("message").asText()).isNotBlank();
    }

    @Test
    void notFoundErrorsUseStandardShape() throws Exception {
        var merchant = createMerchant("merchant-a");
        var missingPaymentIntent = UUID.randomUUID();

        var response = mockMvc.perform(get("/payment_intents/{id}", missingPaymentIntent)
                        .header(API_KEY, merchant.apiKey())
                        .header(REQUEST_ID, "req_missing"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 404, "not_found", "req_missing", "/payment_intents/" + missingPaymentIntent);
        assertThat(error.get("message").asText()).isEqualTo("PaymentIntent was not found");
    }

    @Test
    void unknownRouteErrorsUseStandardShape() throws Exception {
        var response = mockMvc.perform(get("/does-not-exist")
                        .header(REQUEST_ID, "req_unknown_route"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 404, "not_found", "req_unknown_route", "/does-not-exist");
        assertThat(error.get("message").asText()).isEqualTo("Resource was not found");
    }

    @Test
    void conflictErrorsUseStandardShape() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customer = createCustomer(merchant, "buyer-a@example.com");
        createPaymentIntent(merchant, customer, "same-key", 1200);

        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "same-key")
                        .header(REQUEST_ID, "req_conflict")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1201,
                                  "currency": "USD"
                                }
                                """.formatted(customer)))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 409, "conflict", "req_conflict", "/payment_intents");
        assertThat(error.get("message").asText())
                .isEqualTo("Idempotency key was already used with a different request");
    }

    @Test
    void unexpectedErrorsUseStandardShapeWithoutLeakingDetails() throws Exception {
        var response = mockMvc.perform(get("/test-errors/explode")
                        .header(REQUEST_ID, "req_unexpected"))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var error = objectMapper.readTree(response);
        assertStandardError(error, 500, "internal_server_error", "req_unexpected", "/test-errors/explode");
        assertThat(error.get("message").asText()).isEqualTo("Internal server error");
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
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

    private void createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        mockMvc.perform(post("/payment_intents")
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
                .andExpect(status().isCreated());
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

    @TestConfiguration
    static class ErrorEndpointConfiguration {

        @Bean
        ErrorEndpoint errorEndpoint() {
            return new ErrorEndpoint();
        }
    }

    @RestController
    static class ErrorEndpoint {

        @GetMapping("/test-errors/explode")
        void explode() {
            throw new IllegalStateException("simulated internal implementation detail");
        }

        @GetMapping("/test-errors/parameter-validation")
        String validateParameter(@RequestParam @Min(1) int amount) {
            return "amount=%d".formatted(amount);
        }

        @GetMapping("/test-errors/response-status")
        String responseStatus() {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Missing test resource");
        }
    }
}
