package com.thaap.payments.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.idempotency.IdempotencyRepository;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.review.ReviewCase;
import com.thaap.payments.review.ReviewCaseRepository;
import com.thaap.payments.rules.FraudRule;
import com.thaap.payments.rules.FraudRuleRepository;
import com.thaap.payments.webhook.WebhookDeliveryRepository;
import com.thaap.payments.webhook.WebhookEndpointRepository;
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
class MerchantTenantIsolationTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
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
    void merchantFacingApisRejectMissingOrInvalidApiKey() throws Exception {
        mockMvc.perform(post("/customers")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/customers")
                        .header(API_KEY, "invalid-key")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentMethodCreationRejectsCardNumbersThatNormalizeBelowMinimumLength() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customer = createCustomer(merchant, "buyer-a@example.com");

        mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "invalid-card")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4           "
                                }
                                """.formatted(customer)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void merchantCannotFetchAnotherMerchantsPaymentIntent() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentIntentB = createPaymentIntent(merchantB, customerB, "b-create", 1200);

        mockMvc.perform(get("/payment_intents/{id}", paymentIntentB)
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantCannotCreatePaymentIntentForAnotherMerchantsCustomer() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");

        mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-customer")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1200,
                                  "currency": "USD"
                                }
                                """.formatted(customerB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantCannotCreatePaymentMethodForAnotherMerchantsCustomer() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");

        mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-payment-method")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4242424242424242"
                                }
                                """.formatted(customerB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantCannotMutateAnotherMerchantsPaymentIntent() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentIntentB = createPaymentIntent(merchantB, customerB, "b-create", 1200);

        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentB)
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-capture"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentB)
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-cancel"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-refund")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 500
                                }
                                """.formatted(paymentIntentB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeysAreScopedByMerchantAndConflictingWithinMerchant() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");

        var firstA = createPaymentIntentResponse(merchantA, customerA, "shared-key", 1200);
        var replayA = createPaymentIntentResponse(merchantA, customerA, "shared-key", 1200);
        var firstB = createPaymentIntentResponse(merchantB, customerB, "shared-key", 2300);

        assertThat(replayA.get("id").asText()).isEqualTo(firstA.get("id").asText());
        assertThat(firstB.get("merchantId").asText()).isEqualTo(merchantB.id().toString());

        mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "shared-key")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1201,
                                  "currency": "USD"
                                }
                                """.formatted(customerA)))
                .andExpect(status().isConflict());
    }

    @Test
    void webhookDeliveriesAreVisibleOnlyToOwningMerchant() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        createWebhookEndpoint(merchantA, "https://merchant-a.example/webhooks");
        createWebhookEndpoint(merchantB, "https://merchant-b.example/webhooks");

        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        createPaymentIntent(merchantA, customerA, "a-create", 1200);

        var merchantADeliveries = getArray("/webhook_deliveries", merchantA);
        var merchantBDeliveries = getArray("/webhook_deliveries", merchantB);

        assertThat(merchantADeliveries).hasSize(1);
        assertThat(merchantADeliveries.get(0).get("merchantId").asText()).isEqualTo(merchantA.id().toString());
        assertThat(merchantBDeliveries).isEmpty();
    }

    @Test
    void fraudRulesAndReviewsAreVisibleOnlyToOwningMerchant() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        fraudDecisionRepository.save(new FraudDecision(merchantA.id(), "ALLOW"));
        fraudDecisionRepository.save(new FraudDecision(merchantB.id(), "BLOCK"));
        fraudRuleRepository.save(new FraudRule(merchantA.id(), "a-rule"));
        fraudRuleRepository.save(new FraudRule(merchantB.id(), "b-rule"));
        reviewCaseRepository.save(new ReviewCase(merchantA.id(), "OPEN"));
        reviewCaseRepository.save(new ReviewCase(merchantB.id(), "CLOSED"));

        var fraudDecisions = getArray("/fraud/decisions", merchantA);
        var rules = getArray("/rules", merchantA);
        var reviews = getArray("/reviews", merchantA);

        assertThat(fraudDecisions).hasSize(1);
        assertThat(fraudDecisions.get(0).get("action").asText()).isEqualTo("ALLOW");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("name").asText()).isEqualTo("a-rule");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("status").asText()).isEqualTo("OPEN");
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

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        return UUID.fromString(createPaymentIntentResponse(merchant, customerId, idempotencyKey, amount).get("id").asText());
    }

    private JsonNode createPaymentIntentResponse(MerchantAccess merchant, UUID customerId, String idempotencyKey,
            long amount) throws Exception {
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

    private JsonNode getArray(String path, MerchantAccess merchant) throws Exception {
        var response = mockMvc.perform(get(path)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
