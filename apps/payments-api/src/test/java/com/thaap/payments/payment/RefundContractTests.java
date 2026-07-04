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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class RefundContractTests {

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
    void automaticCaptureCanBeRefundedWithDurableLineageAndExactLedgerReversal() throws Exception {
        MerchantAccess merchant = createMerchant("automatic-refund-merchant");
        UUID customerId = createCustomer(merchant, "automatic-refund@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "automatic-refund-method",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "automatic-refund-intent", 4200,
                "AUTOMATIC");
        createWebhookEndpoint(merchant, "https://automatic-refund.example/webhooks");
        UUID paymentAttemptId = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                "automatic-refund-confirm");
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");
        UUID authorizationId = authorizationId(paymentAttemptId);
        int fraudDecisionCount = countRows("fraud_decisions");
        int featureSnapshotCount = countRows("feature_snapshots");

        JsonNode refund = createRefund(merchant, paymentIntentId, 4200, "automatic-refund",
                "req-automatic-refund");
        UUID refundId = UUID.fromString(refund.get("id").asText());

        assertRefundResponse(refund, merchant.id(), paymentIntentId, paymentAttemptId, authorizationId, 4200);
        assertThat(getRefund(merchant, refundId)).isEqualTo(refund);
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REFUNDED");
        assertThat(countRows("refunds")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(4);
        assertBalancedRefundLedger(refundId, paymentIntentId, 4200, "USD");
        assertThat(countWebhookDeliveries("refund.created", refundId)).isEqualTo(1);
        assertThat(countWebhookDeliveries("payment_intent.refunded", paymentIntentId)).isZero();
        assertSafeRefundAudit(refundId, paymentIntentId, paymentAttemptId, authorizationId);
        assertThat(countRows("fraud_decisions")).isEqualTo(fraudDecisionCount);
        assertThat(countRows("feature_snapshots")).isEqualTo(featureSnapshotCount);

        JsonNode ledgerEntries = listLedgerEntries(merchant);
        assertThat(ledgerEntries).hasSize(4);
        assertThat(findLedgerEntry(ledgerEntries, "REFUND", "MERCHANT_PAYABLE").get("paymentIntentId").asText())
                .isEqualTo(paymentIntentId.toString());
        assertThat(findLedgerEntry(ledgerEntries, "REFUND", "MERCHANT_PAYABLE").get("refundId").asText())
                .isEqualTo(refundId.toString());
        assertThat(findLedgerEntry(ledgerEntries, "PAYMENT_INTENT_CAPTURE", "MERCHANT_PAYABLE")
                .get("refundId").isNull()).isTrue();
    }

    @Test
    void manualCaptureCanBeRefundedAndIdempotentReplayReturnsTheSameRefund() throws Exception {
        MerchantAccess merchant = createMerchant("manual-refund-merchant");
        UUID customerId = createCustomer(merchant, "manual-refund@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "manual-refund-method",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "manual-refund-intent", 5100, "MANUAL");
        UUID paymentAttemptId = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                "manual-refund-confirm");
        capturePaymentIntent(merchant, paymentIntentId, "manual-refund-capture");

        JsonNode first = createRefund(merchant, paymentIntentId, 5100, "manual-refund", "req-manual-refund");
        JsonNode replay = createRefund(merchant, paymentIntentId, 5100, "manual-refund",
                "req-manual-refund-replay");

        assertThat(replay).isEqualTo(first);
        assertThat(first.get("paymentAttemptId").asText()).isEqualTo(paymentAttemptId.toString());
        assertThat(countRows("refunds")).isEqualTo(1);
        assertThat(countLedgerEntries("REFUND")).isEqualTo(2);
        assertThat(countAuditLogs("refund.created")).isEqualTo(1);
    }

    @Test
    void changedIdempotentRequestAndSecondRefundAreRejectedWithoutDuplicateTruth() throws Exception {
        MerchantAccess merchant = createMerchant("duplicate-refund-merchant");
        UUID customerId = createCustomer(merchant, "duplicate-refund@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "duplicate-refund-method",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "duplicate-refund-intent", 6200,
                "AUTOMATIC");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "duplicate-refund-confirm");
        createRefund(merchant, paymentIntentId, 6200, "duplicate-refund", "req-duplicate-refund");

        mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "duplicate-refund")
                        .contentType(APPLICATION_JSON)
                        .content(refundRequest(paymentIntentId, 6100)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "second-refund")
                        .contentType(APPLICATION_JSON)
                        .content(refundRequest(paymentIntentId, 6200)))
                .andExpect(status().isConflict());

        assertThat(countRows("refunds")).isEqualTo(1);
        assertThat(countLedgerEntries("REFUND")).isEqualTo(2);
        assertThat(countAuditLogs("refund.created")).isEqualTo(1);
    }

    @Test
    void partialAndOverRefundsAreRejectedBeforeAnyRefundSideEffect() throws Exception {
        MerchantAccess merchant = createMerchant("amount-refund-merchant");
        UUID customerId = createCustomer(merchant, "amount-refund@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "amount-refund-method",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "amount-refund-intent", 7300,
                "AUTOMATIC");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "amount-refund-confirm");

        assertRefundConflict(merchant, paymentIntentId, 7200, "partial-refund",
                "Refund amount must equal captured PaymentIntent amount until partial refunds are supported");
        assertRefundConflict(merchant, paymentIntentId, 7400, "over-refund",
                "Refund amount must equal captured PaymentIntent amount until partial refunds are supported");

        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");
        assertThat(countRows("refunds")).isZero();
        assertThat(countLedgerEntries("REFUND")).isZero();
    }

    @Test
    void nonCapturedPaymentStatesCannotBeRefunded() throws Exception {
        MerchantAccess merchant = createMerchant("invalid-state-refund-merchant");
        UUID customerId = createCustomer(merchant, "invalid-state-refund@example.com");
        UUID approvedMethodId = createPaymentMethod(merchant, customerId, "invalid-approved-method",
                "4999990000000001");
        UUID declinedMethodId = createPaymentMethod(merchant, customerId, "invalid-declined-method",
                "4999990000000002");
        UUID threeDsMethodId = createPaymentMethod(merchant, customerId, "invalid-3ds-method",
                "4999990000000008");

        UUID unconfirmed = createPaymentIntent(merchant, customerId, "unconfirmed-refund-intent", 1100, "AUTOMATIC");
        UUID manualUncaptured = createPaymentIntent(merchant, customerId, "manual-uncaptured-intent", 1200, "MANUAL");
        UUID declined = createPaymentIntent(merchant, customerId, "declined-refund-intent", 1300, "AUTOMATIC");
        UUID threeDs = createPaymentIntent(merchant, customerId, "3ds-refund-intent", 1400, "AUTOMATIC");
        UUID canceled = createPaymentIntent(merchant, customerId, "canceled-refund-intent", 1500, "AUTOMATIC");
        confirmPaymentIntent(merchant, manualUncaptured, approvedMethodId, "manual-uncaptured-confirm");
        confirmPaymentIntent(merchant, declined, declinedMethodId, "declined-refund-confirm");
        confirmPaymentIntent(merchant, threeDs, threeDsMethodId, "3ds-refund-confirm");
        cancelPaymentIntent(merchant, canceled, "canceled-refund-cancel");

        assertRefundStateConflict(merchant, unconfirmed, 1100, "unconfirmed-refund", "REQUIRES_CONFIRMATION");
        assertRefundStateConflict(merchant, manualUncaptured, 1200, "manual-uncaptured-refund", "REQUIRES_CAPTURE");
        assertRefundStateConflict(merchant, declined, 1300, "declined-refund", "REQUIRES_PAYMENT_METHOD");
        assertRefundStateConflict(merchant, threeDs, 1400, "3ds-refund", "REQUIRES_ACTION");
        assertRefundStateConflict(merchant, canceled, 1500, "canceled-refund", "CANCELED");

        assertThat(countRows("refunds")).isZero();
        assertThat(countLedgerEntries("REFUND")).isZero();
    }

    @Test
    void capturedStatusWithoutCompleteCaptureEvidenceCannotCreateRefundTruth() throws Exception {
        MerchantAccess merchant = createMerchant("missing-evidence-refund-merchant");
        UUID customerId = createCustomer(merchant, "missing-evidence-refund@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "missing-evidence-method",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "missing-evidence-intent", 8400,
                "AUTOMATIC");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "missing-evidence-confirm");
        jdbcTemplate.update("""
                DELETE FROM ledger_entries
                WHERE payment_intent_id = ?
                  AND source_type = 'PAYMENT_INTENT_CAPTURE'
                """, paymentIntentId);

        assertRefundConflict(merchant, paymentIntentId, 8400, "missing-evidence-refund",
                "PaymentIntent does not have complete capture ledger evidence");

        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");
        assertThat(countRows("refunds")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void refundCreateReadAndLedgerVisibilityAreTenantScoped() throws Exception {
        MerchantAccess merchantA = createMerchant("refund-tenant-a");
        MerchantAccess merchantB = createMerchant("refund-tenant-b");
        UUID customerA = createCustomer(merchantA, "refund-tenant-a@example.com");
        UUID paymentMethodA = createPaymentMethod(merchantA, customerA, "refund-tenant-a-method",
                "4999990000000001");
        UUID paymentIntentA = createPaymentIntent(merchantA, customerA, "refund-tenant-a-intent", 9500,
                "AUTOMATIC");
        confirmPaymentIntent(merchantA, paymentIntentA, paymentMethodA, "refund-tenant-a-confirm");
        JsonNode refund = createRefund(merchantA, paymentIntentA, 9500, "refund-tenant-a-create",
                "req-refund-tenant-a");
        UUID refundId = UUID.fromString(refund.get("id").asText());

        mockMvc.perform(get("/refunds/{id}", refundId)
                        .header(API_KEY, merchantB.apiKey()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchantB.apiKey())
                        .header(IDEMPOTENCY_KEY, "refund-tenant-cross-create")
                        .contentType(APPLICATION_JSON)
                        .content(refundRequest(paymentIntentA, 9500)))
                .andExpect(status().isNotFound());

        assertThat(listLedgerEntries(merchantA)).hasSize(4);
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

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount,
            String captureMethod) throws Exception {
        String response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": %d,
                                  "currency": "USD",
                                  "captureMethod": "%s"
                                }
                                """.formatted(customerId, amount, captureMethod)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void createWebhookEndpoint(MerchantAccess merchant, String url) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .header("Idempotency-Key", "webhook-" + merchant.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isCreated());
    }

    private UUID confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
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
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void capturePaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().isOk());
    }

    private void cancelPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().isOk());
    }

    private JsonNode createRefund(MerchantAccess merchant, UUID paymentIntentId, long amount, String idempotencyKey,
            String requestId) throws Exception {
        String response = mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(REQUEST_ID, requestId)
                        .contentType(APPLICATION_JSON)
                        .content(refundRequest(paymentIntentId, amount)))
                .andExpect(status().isCreated())
                .andExpect(header().string(REQUEST_ID, requestId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getRefund(MerchantAccess merchant, UUID refundId) throws Exception {
        String response = mockMvc.perform(get("/refunds/{id}", refundId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode listLedgerEntries(MerchantAccess merchant) throws Exception {
        String response = mockMvc.perform(get("/ledger/entries")
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertRefundConflict(MerchantAccess merchant, UUID paymentIntentId, long amount,
            String idempotencyKey, String expectedMessage) throws Exception {
        String response = mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(refundRequest(paymentIntentId, amount)))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(response).get("message").asText()).isEqualTo(expectedMessage);
    }

    private void assertRefundStateConflict(MerchantAccess merchant, UUID paymentIntentId, long amount,
            String idempotencyKey, String statusName) throws Exception {
        assertRefundConflict(merchant, paymentIntentId, amount, idempotencyKey,
                "PaymentIntent cannot be refunded from status " + statusName);
    }

    private void assertRefundResponse(JsonNode refund, UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID authorizationId, long amount) {
        assertThat(refund.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(refund.get("paymentIntentId").asText()).isEqualTo(paymentIntentId.toString());
        assertThat(refund.get("paymentAttemptId").asText()).isEqualTo(paymentAttemptId.toString());
        assertThat(refund.get("authorizationId").asText()).isEqualTo(authorizationId.toString());
        assertThat(refund.get("amount").asLong()).isEqualTo(amount);
        assertThat(refund.get("currency").asText()).isEqualTo("USD");
        assertThat(refund.get("status").asText()).isEqualTo("RECORDED");
        assertThat(refund.get("createdAt").asText()).isNotBlank();
    }

    private void assertBalancedRefundLedger(UUID refundId, UUID paymentIntentId, long amount, String currency) {
        Set<String> groups = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT transaction_group_id
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                  AND source_id = ?
                """, String.class, refundId));
        assertThat(groups).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0)
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                  AND source_id = ?
                """, Long.class, refundId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                  AND source_id = ?
                  AND payment_intent_id = ?
                  AND refund_id = ?
                  AND amount = ?
                  AND currency = ?
                """, Integer.class, refundId, paymentIntentId, refundId, amount, currency)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                  AND source_id = ?
                  AND account = 'MERCHANT_PAYABLE'
                  AND direction = 'DEBIT'
                """, Integer.class, refundId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                  AND source_id = ?
                  AND account = 'CUSTOMER_RECEIVABLE'
                  AND direction = 'CREDIT'
                """, Integer.class, refundId)).isEqualTo(1);
    }

    private void assertSafeRefundAudit(UUID refundId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID authorizationId) {
        MapRow audit = jdbcTemplate.queryForObject("""
                SELECT aggregate_type, aggregate_id, request_id, before_json, after_json
                FROM audit_logs
                WHERE action = 'refund.created'
                """, (resultSet, rowNumber) -> new MapRow(
                resultSet.getString("aggregate_type"),
                UUID.fromString(resultSet.getString("aggregate_id")),
                resultSet.getString("request_id"),
                resultSet.getString("before_json"),
                resultSet.getString("after_json")));
        assertThat(audit).isNotNull();
        assertThat(audit.aggregateType()).isEqualTo("refund");
        assertThat(audit.aggregateId()).isEqualTo(refundId);
        assertThat(audit.requestId()).isEqualTo("req-automatic-refund");
        assertThat(audit.beforeJson()).contains(paymentIntentId.toString(), paymentAttemptId.toString(),
                authorizationId.toString(), "captureTransactionGroupId");
        assertThat(audit.afterJson()).contains(refundId.toString(), "RECORDED", "REFUNDED",
                "refundTransactionGroupId", "ledgerEntryIds");
        assertThat(audit.beforeJson() + audit.afterJson())
                .doesNotContain("automatic-refund@example.com", "4999990000000001", "X-Api-Key");
    }

    private JsonNode findLedgerEntry(JsonNode entries, String sourceType, String account) {
        for (JsonNode entry : entries) {
            if (sourceType.equals(entry.get("sourceType").asText())
                    && account.equals(entry.get("account").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Ledger entry was not found for " + sourceType + "/" + account);
    }

    private UUID authorizationId(UUID paymentAttemptId) {
        return jdbcTemplate.queryForObject("""
                SELECT id
                FROM authorizations
                WHERE payment_attempt_id = ?
                """, UUID.class, paymentAttemptId);
    }

    private String paymentIntentStatus(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countLedgerEntries(String sourceType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = ?
                """, Integer.class, sourceType);
    }

    private int countWebhookDeliveries(String eventType, UUID resourceId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = ?
                  AND resource_id = ?
                """, Integer.class, eventType, resourceId);
    }

    private int countAuditLogs(String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = ?
                """, Integer.class, action);
    }

    private String refundRequest(UUID paymentIntentId, long amount) {
        return """
                {
                  "paymentIntentId": "%s",
                  "amount": %d
                }
                """.formatted(paymentIntentId, amount);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }

    private record MapRow(String aggregateType, UUID aggregateId, String requestId, String beforeJson,
            String afterJson) {
    }
}
