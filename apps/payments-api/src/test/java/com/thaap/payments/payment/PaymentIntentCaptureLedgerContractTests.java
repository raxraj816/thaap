package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentIntentCaptureLedgerContractTests {

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
    void approvedConfirmCanBeCapturedWithBalancedLedgerEntriesAndSafeLedgerReadApi() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "capture-pm-approved",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "capture-pi-approved", 1200);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "capture-confirm-approved");

        JsonNode capture = capturePaymentIntent(merchant, paymentIntentId, "capture-approved", "req-capture-approved");

        assertThat(capture.get("status").asText()).isEqualTo("CAPTURED");
        assertThat(statusFor(paymentIntentId)).isEqualTo("CAPTURED");
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertThat(countWebhookDeliveries("payment_intent.captured")).isEqualTo(1);
        assertThat(countAuditLogsByAction("payment_intent.captured")).isEqualTo(1);
        assertBalancedCaptureLedger(paymentIntentId, 1200, "USD");

        JsonNode ledgerEntries = listLedgerEntries(merchant);
        assertThat(ledgerEntries).hasSize(2);
        assertThat(ledgerEntries.get(0).has("merchantId")).isFalse();
        assertThat(ledgerEntries.get(0).get("sourceType").asText()).isEqualTo("PAYMENT_INTENT_CAPTURE");
        assertThat(ledgerEntries.get(0).get("sourceId").asText()).isEqualTo(paymentIntentId.toString());

        JsonNode limitedLedgerEntries = listLedgerEntries(merchant, 1);
        assertThat(limitedLedgerEntries).hasSize(1);

        mockMvc.perform(get("/ledger/entries")
                        .header(API_KEY, merchant.apiKey())
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotentCaptureReplayDoesNotDuplicateLedgerWebhookOrAuditTruth() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "capture-pm-replay",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "capture-pi-replay", 2300);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "capture-confirm-replay");

        JsonNode first = capturePaymentIntent(merchant, paymentIntentId, "capture-replay", "req-capture-replay");
        JsonNode replay = capturePaymentIntent(merchant, paymentIntentId, "capture-replay", "req-capture-replay-2");

        assertThat(replay).isEqualTo(first);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertThat(countWebhookDeliveries("payment_intent.captured")).isEqualTo(1);
        assertThat(countAuditLogsByAction("payment_intent.captured")).isEqualTo(1);
        assertBalancedCaptureLedger(paymentIntentId, 2300, "USD");
    }

    @Test
    void separateCaptureKeyAfterCaptureIsRejectedWithoutDuplicateLedgerEntries() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "capture-pm-double",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "capture-pi-double", 3400);
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "capture-confirm-double");
        capturePaymentIntent(merchant, paymentIntentId, "capture-first", "req-capture-first");

        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-second")
                        .header(REQUEST_ID, "req-capture-second"))
                .andExpect(status().isConflict())
                .andExpect(header().string(REQUEST_ID, "req-capture-second"));

        assertThat(statusFor(paymentIntentId)).isEqualTo("CAPTURED");
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertThat(countAuditLogsByAction("payment_intent.captured")).isEqualTo(1);
    }

    @Test
    void captureBeforeAuthorizationIsRejectedWithoutLedgerEntries() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "capture-pi-invalid-state", 4500);

        JsonNode error = capturePaymentIntentExpectingConflict(merchant, paymentIntentId,
                "capture-invalid-state", "req-capture-invalid-state");

        assertThat(error.get("message").asText())
                .isEqualTo("PaymentIntent cannot be captured from status REQUIRES_CONFIRMATION");
        assertThat(statusFor(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void captureAfterDeclineOrThreeDsRequiredIsRejectedWithoutLedgerEntries() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID declinedPaymentMethodId = createPaymentMethod(merchant, customerId, "capture-pm-decline",
                "4999990000000002");
        UUID threeDsPaymentMethodId = createPaymentMethod(merchant, customerId, "capture-pm-3ds",
                "4999990000000008");
        UUID declinedPaymentIntentId = createPaymentIntent(merchant, customerId, "capture-pi-decline", 5600);
        UUID threeDsPaymentIntentId = createPaymentIntent(merchant, customerId, "capture-pi-3ds", 6700);
        confirmPaymentIntent(merchant, declinedPaymentIntentId, declinedPaymentMethodId, "capture-confirm-decline");
        confirmPaymentIntent(merchant, threeDsPaymentIntentId, threeDsPaymentMethodId, "capture-confirm-3ds");

        JsonNode declinedError = capturePaymentIntentExpectingConflict(merchant, declinedPaymentIntentId,
                "capture-decline", "req-capture-decline");
        JsonNode threeDsError = capturePaymentIntentExpectingConflict(merchant, threeDsPaymentIntentId,
                "capture-3ds", "req-capture-3ds");

        assertThat(declinedError.get("message").asText())
                .isEqualTo("PaymentIntent cannot be captured from status REQUIRES_PAYMENT_METHOD");
        assertThat(threeDsError.get("message").asText())
                .isEqualTo("PaymentIntent cannot be captured from status REQUIRES_ACTION");
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void merchantCannotCaptureOrListAnotherMerchantsLedgerEntries() throws Exception {
        MerchantAccess merchantA = createMerchant("merchant-a");
        MerchantAccess merchantB = createMerchant("merchant-b");
        UUID customerA = createCustomer(merchantA, "buyer-a@example.com");
        UUID paymentMethodA = createPaymentMethod(merchantA, customerA, "capture-pm-tenant-a",
                "4999990000000001");
        UUID paymentIntentA = createPaymentIntent(merchantA, customerA, "capture-pi-tenant-a", 7800);
        confirmPaymentIntent(merchantA, paymentIntentA, paymentMethodA, "capture-confirm-tenant-a");
        capturePaymentIntent(merchantA, paymentIntentA, "capture-tenant-a", "req-capture-tenant-a");

        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentA)
                        .header(API_KEY, merchantB.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-merchant-capture"))
                .andExpect(status().isNotFound());

        assertThat(listLedgerEntries(merchantA)).hasSize(2);
        assertThat(listLedgerEntries(merchantB)).hasSize(0);
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

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        String response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": %d,
                                  "currency": "USD",
                                  "captureMethod": "MANUAL"
                                }
                                """.formatted(customerId, amount)))
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

    private JsonNode capturePaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey,
            String requestId) throws Exception {
        String response = mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(REQUEST_ID, requestId))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID, requestId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode capturePaymentIntentExpectingConflict(MerchantAccess merchant, UUID paymentIntentId,
            String idempotencyKey, String requestId) throws Exception {
        String response = mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(REQUEST_ID, requestId))
                .andExpect(status().isConflict())
                .andExpect(header().string(REQUEST_ID, requestId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode listLedgerEntries(MerchantAccess merchant) throws Exception {
        return listLedgerEntries(merchant, null);
    }

    private JsonNode listLedgerEntries(MerchantAccess merchant, Integer limit) throws Exception {
        MockHttpServletRequestBuilder request = get("/ledger/entries")
                .header(API_KEY, merchant.apiKey());
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertBalancedCaptureLedger(UUID paymentIntentId, long amount, String currency) {
        Set<String> groups = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT transaction_group_id
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                """, String.class, paymentIntentId));
        assertThat(groups).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                """, Long.class, paymentIntentId)).isEqualTo(0L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                  AND amount = ?
                  AND currency = ?
                """, Integer.class, paymentIntentId, amount, currency)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                  AND account = 'CUSTOMER_RECEIVABLE'
                  AND direction = 'DEBIT'
                """, Integer.class, paymentIntentId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                  AND account = 'MERCHANT_PAYABLE'
                  AND direction = 'CREDIT'
                """, Integer.class, paymentIntentId)).isEqualTo(1);
    }

    private String statusFor(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
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

    private int countAuditLogsByAction(String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = ?
                """, Integer.class, action);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
