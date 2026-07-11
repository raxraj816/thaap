package com.thaap.payments.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.review.ReviewCase;
import com.thaap.payments.review.ReviewCaseRepository;
import com.thaap.payments.rules.FraudRule;
import com.thaap.payments.rules.FraudRuleRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.webhook.WebhookEventType;
import java.util.List;
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
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FraudRuleRepository fraudRuleRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        databaseCleaner.clean();
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
    void paymentMethodCreationReturnsSafeCardMetadata() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customer = createCustomer(merchant, "buyer-a@example.com");

        var response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "card-metadata")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4242424242424242"
                                }
                                """.formatted(customer)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);

        assertThat(json.get("cardBin").asText()).isEqualTo("424242");
        assertThat(json.get("cardLast4").asText()).isEqualTo("4242");
        assertThat(json.get("cardFunding").asText()).isEqualTo("CREDIT");
        assertThat(json.get("cardCountry").asText()).isEqualTo("US");
        assertThat(response).doesNotContain("4242424242424242");
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
    void paymentIntentDefaultsToAutomaticCaptureMethod() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customer = createCustomer(merchant, "buyer-a@example.com");

        var paymentIntent = createPaymentIntentResponse(merchant, customer, "capture-default", 1200);

        assertThat(paymentIntent.get("captureMethod").asText()).isEqualTo("AUTOMATIC");
    }

    @Test
    void webhookDeliveriesAreVisibleOnlyToOwningMerchant() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        UUID endpointA = createWebhookEndpoint(merchantA, "https://merchant-a.example/webhooks");
        UUID endpointB = createWebhookEndpoint(merchantB, "https://merchant-b.example/webhooks");

        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        createPaymentIntent(merchantA, customerA, "a-create", 1200);

        var merchantADeliveries = getArray("/webhook_deliveries", merchantA);
        var merchantBDeliveries = getArray("/webhook_deliveries", merchantB);

        assertThat(merchantADeliveries).hasSize(1);
        assertThat(merchantADeliveries.get(0).get("merchantId").asText()).isEqualTo(merchantA.id().toString());
        assertThat(merchantADeliveries.get(0).get("attemptCount").asInt()).isZero();
        assertThat(merchantADeliveries.get(0).get("nextAttemptAt").isNull()).isTrue();
        assertThat(merchantADeliveries.get(0).get("lastAttemptAt").isNull()).isTrue();
        assertThat(merchantADeliveries.get(0).get("deliveredAt").isNull()).isTrue();
        assertThat(merchantADeliveries.get(0).get("lastError").isNull()).isTrue();
        assertThat(merchantBDeliveries).isEmpty();

        UUID deliveryA = UUID.fromString(merchantADeliveries.get(0).get("id").asText());
        String detail = mockMvc.perform(get("/webhook_deliveries/{id}", deliveryA)
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(detail)
                .doesNotContain("payload", "signingSecret", "claimToken", "leaseExpiresAt",
                        "merchant-a.example");
        mockMvc.perform(get("/webhook_deliveries").param("limit", "101")
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/webhook_deliveries/{id}/attempts", deliveryA).param("limit", "101")
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/webhook_deliveries/{id}", deliveryA)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/webhook_deliveries/{id}/attempts", deliveryA)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/webhook_endpoints/{id}/rotate_secret", endpointA)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/webhook_endpoints/{id}/disable", endpointB)
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isNotFound());
        String disabledEndpoint = mockMvc.perform(post("/webhook_endpoints/{id}/disable", endpointB)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode disabledEndpointJson = objectMapper.readTree(disabledEndpoint);
        assertThat(disabledEndpointJson.get("operationalStatus").asText()).isEqualTo("DISABLED");
        assertThat(disabledEndpointJson.get("enabledEvents")).hasSize(WebhookEventType.values().length);
        assertThat(disabledEndpointJson.get("signingSecret").isNull()).isTrue();
        mockMvc.perform(post("/webhook_endpoints/{id}/rotate_secret", endpointB)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isConflict());

        createPaymentIntent(merchantA, customerA, "a-create-2", 1300);
        createPaymentIntent(merchantA, customerA, "a-create-3", 1400);
        String firstPageBody = mockMvc.perform(get("/webhook_deliveries")
                        .param("limit", "2")
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode firstPage = objectMapper.readTree(firstPageBody);
        JsonNode cursor = firstPage.get(1);
        String secondPageBody = mockMvc.perform(get("/webhook_deliveries")
                        .param("limit", "2")
                        .param("beforeCreatedAt", cursor.get("createdAt").asText())
                        .param("beforeId", cursor.get("id").asText())
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode secondPage = objectMapper.readTree(secondPageBody);
        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).get("id").asText())
                .isNotEqualTo(firstPage.get(0).get("id").asText())
                .isNotEqualTo(firstPage.get(1).get("id").asText());
        mockMvc.perform(get("/webhook_deliveries")
                        .param("beforeId", cursor.get("id").asText())
                        .header(API_KEY, merchantA.apiKey()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fraudDecisionsRulesAndReviewsAreVisibleOnlyToOwningMerchant() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentMethodA = createPaymentMethod(merchantA, customerA, "tenant-fraud-pm-a");
        var paymentMethodB = createPaymentMethod(merchantB, customerB, "tenant-fraud-pm-b");
        var paymentIntentA = createPaymentIntent(merchantA, customerA, "tenant-fraud-pi-a", 1200);
        var paymentIntentB = createPaymentIntent(merchantB, customerB, "tenant-fraud-pi-b", 2300);
        confirmPaymentIntent(merchantA, paymentIntentA, paymentMethodA, "tenant-fraud-confirm-a");
        confirmPaymentIntent(merchantB, paymentIntentB, paymentMethodB, "tenant-fraud-confirm-b");
        fraudRuleRepository.save(new FraudRule(merchantA.id(), "a-rule"));
        fraudRuleRepository.save(new FraudRule(merchantB.id(), "b-rule"));
        reviewCaseRepository.save(new ReviewCase(merchantA.id(), "OPEN"));
        reviewCaseRepository.save(new ReviewCase(merchantB.id(), "CLOSED"));

        var fraudDecisions = getArray("/fraud/decisions", merchantA);
        var rules = getArray("/rules", merchantA);
        var reviews = getArray("/reviews", merchantA);

        assertThat(fraudDecisions).hasSize(1);
        assertThat(fraudDecisions.get(0).get("action").asText()).isEqualTo("ALLOW");
        assertThat(fraudDecisions.get(0).get("riskLevel").asText()).isEqualTo("LOW");
        var fraudDecisionId = UUID.fromString(fraudDecisions.get(0).get("id").asText());
        var fraudDecisionDetail = getObject("/fraud/decisions/" + fraudDecisionId, merchantA);
        assertThat(fraudDecisionDetail.get("featureSnapshot").get("featureVersion").asText())
                .isEqualTo("features_v1");
        JsonNode features = fraudDecisionDetail.get("featureSnapshot").get("features");
        List<String> featureNames = new java.util.ArrayList<>();
        features.fieldNames().forEachRemaining(featureNames::add);
        assertThat(featureNames).containsExactly(
                "card.country", "card.funding", "payment.amount_minor", "payment.attempt_number",
                "payment.capture_method", "payment.currency", "traffic.origin");
        assertThat(features.toString()).doesNotContain(
                "card_bin", "card_last4", "fingerprint", "customer", "payment_intent_id",
                "payment_attempt_id", "payment_method_id", "metadata", "description", "idempotency");
        mockMvc.perform(get("/fraud/decisions/{id}", fraudDecisionId)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isNotFound());
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("name").asText()).isEqualTo("a-rule");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("status").asText()).isEqualTo("OPEN");
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
        assertThat(json.get("apiKeyPrefix").asText()).isNotBlank();
        assertThat(json.get("apiKey").asText()).startsWith(json.get("apiKeyPrefix").asText());
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

    private void confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isCreated());
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

    private UUID createWebhookEndpoint(MerchantAccess merchant, String url) throws Exception {
        var response = mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .header("Idempotency-Key", "webhook-" + merchant.id() + "-" + url.hashCode())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        assertThat(json.get("signingSecret").asText()).startsWith("whsec_");
        assertThat(json.get("signingSecret").asText()).startsWith(json.get("signingSecretPrefix").asText());
        return UUID.fromString(json.get("id").asText());
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

    private JsonNode getObject(String path, MerchantAccess merchant) throws Exception {
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
