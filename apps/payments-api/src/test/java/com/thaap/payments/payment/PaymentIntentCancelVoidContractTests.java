package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentIntentCancelVoidContractTests {

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
    void approvedAutomaticConfirmCapturesWithBalancedLedgerAuditAndWebhookTruth() throws Exception {
        MerchantAccess merchant = createMerchant("automatic-merchant");
        UUID customerId = createCustomer(merchant, "automatic@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "automatic-pm", "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "automatic-pi", 1200, "AUTOMATIC");
        createWebhookEndpoint(merchant, "https://automatic.example/webhooks");

        JsonNode attempt = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "automatic-confirm");

        assertThat(attempt.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertThat(captureLedgerBalance(paymentIntentId)).isZero();
        assertThat(countWebhookDeliveries("payment_attempt.created")).isEqualTo(1);
        assertThat(countWebhookDeliveries("payment_intent.captured")).isEqualTo(1);
        assertThat(countAuditLogs("payment_intent.confirmed")).isEqualTo(1);
        assertThat(countAuditLogs("authorization.approved")).isEqualTo(1);
        assertThat(countAuditLogs("payment_intent.captured")).isEqualTo(1);
        JsonNode captureAudit = auditAfter("payment_intent.captured");
        assertThat(captureAudit.get("captureMethod").asText()).isEqualTo("AUTOMATIC");
        assertThat(captureAudit.get("captureTrigger").asText()).isEqualTo("AUTOMATIC_CONFIRM");

        JsonNode replay = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "automatic-confirm");
        assertThat(replay).isEqualTo(attempt);
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertThat(countAuditLogs("payment_intent.captured")).isEqualTo(1);
        assertThat(countWebhookDeliveries("payment_intent.captured")).isEqualTo(1);

        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "automatic-public-capture"))
                .andExpect(status().isConflict());
    }

    @Test
    void approvedManualConfirmWaitsForSeparateCaptureWithoutLedgerTruth() throws Exception {
        MerchantAccess merchant = createMerchant("manual-merchant");
        UUID customerId = createCustomer(merchant, "manual@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "manual-pm", "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "manual-pi", 2300, "MANUAL");

        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "manual-confirm");

        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countAuditLogs("payment_intent.captured")).isZero();
        assertThat(countWebhookDeliveries("payment_intent.captured")).isZero();
    }

    @Test
    void cancelBeforeAuthorizationCreatesNoVoidAndRecordsSafeOperationalTruth() throws Exception {
        MerchantAccess merchant = createMerchant("preauth-cancel-merchant");
        UUID customerId = createCustomer(merchant, "preauth@example.com");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "preauth-pi", 3400, "MANUAL");
        createWebhookEndpoint(merchant, "https://preauth.example/webhooks");

        JsonNode canceled = cancelPaymentIntent(merchant, paymentIntentId, "preauth-cancel", "req-preauth-cancel");

        assertThat(canceled.get("status").asText()).isEqualTo("CANCELED");
        assertThat(countRows("authorization_voids")).isZero();
        assertThat(countWebhookDeliveries("payment_intent.canceled")).isEqualTo(1);
        assertThat(countAuditLogs("payment_intent.canceled")).isEqualTo(1);
        JsonNode audit = auditAfter("payment_intent.canceled");
        assertThat(audit.get("voidRequired").asBoolean()).isFalse();
        assertThat(audit.has("authorizationId")).isFalse();
        assertThat(audit.has("authorizationVoidId")).isFalse();
    }

    @Test
    void cancelApprovedManualAuthorizationCreatesAppendOnlyVoidAndBlocksCapture() throws Exception {
        MerchantAccess merchant = createMerchant("void-merchant");
        UUID customerId = createCustomer(merchant, "void@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "void-pm", "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "void-pi", 4500, "MANUAL");
        createWebhookEndpoint(merchant, "https://void.example/webhooks");
        JsonNode attempt = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "void-confirm");

        JsonNode first = cancelPaymentIntent(merchant, paymentIntentId, "void-cancel", "req-void-cancel");
        JsonNode replay = cancelPaymentIntent(merchant, paymentIntentId, "void-cancel", "req-void-cancel-replay");

        assertThat(replay).isEqualTo(first);
        assertThat(first.get("status").asText()).isEqualTo("CANCELED");
        assertThat(countRows("authorization_voids")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countWebhookDeliveries("payment_intent.canceled")).isEqualTo(1);
        assertThat(countAuditLogs("payment_intent.canceled")).isEqualTo(1);
        JsonNode audit = auditAfter("payment_intent.canceled");
        assertThat(audit.get("voidRequired").asBoolean()).isTrue();
        assertThat(audit.get("authorizationId").asText()).isNotBlank();
        assertThat(audit.get("authorizationVoidId").asText()).isNotBlank();
        assertThat(audit.get("authorizationVoidStatus").asText()).isEqualTo("RECORDED");
        assertThat(audit.get("authorizationVoidReason").asText()).isEqualTo("MERCHANT_CANCELED");

        JsonNode authorization = getAuthorization(merchant, UUID.fromString(attempt.get("id").asText()));
        assertThat(authorization.get("status").asText()).isEqualTo("APPROVED");
        assertThat(authorization.get("voided").asBoolean()).isTrue();
        assertThat(authorization.get("authorizationVoidId").asText()).isNotBlank();
        assertThat(authorization.get("authorizationVoidStatus").asText()).isEqualTo("RECORDED");
        assertThat(authorization.get("authorizationVoidReason").asText()).isEqualTo("MERCHANT_CANCELED");
        assertThat(authorization.get("voidedAt").asText()).isNotBlank();

        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-after-void"))
                .andExpect(status().isConflict());
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void reservedAuthorizedStateAlsoRequiresVoidEvidenceBeforeCancellation() throws Exception {
        MerchantAccess merchant = createMerchant("authorized-state-merchant");
        UUID customerId = createCustomer(merchant, "authorized-state@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "authorized-state-pm",
                "4999990000000001");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "authorized-state-pi", 4550, "MANUAL");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "authorized-state-confirm");
        jdbcTemplate.update("""
                UPDATE payment_intents
                SET status = 'AUTHORIZED'
                WHERE id = ?
                """, paymentIntentId);

        cancelPaymentIntent(merchant, paymentIntentId, "authorized-state-cancel",
                "req-authorized-state-cancel");

        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CANCELED");
        assertThat(countRows("authorization_voids")).isEqualTo(1);
        assertThat(auditAfter("payment_intent.canceled").get("voidRequired").asBoolean()).isTrue();
    }

    @Test
    void declineAndThreeDsCancellationDoNotInventVoidTruth() throws Exception {
        MerchantAccess merchant = createMerchant("non-approved-cancel-merchant");
        UUID customerId = createCustomer(merchant, "nonapproved@example.com");
        UUID declinedMethod = createPaymentMethod(merchant, customerId, "declined-pm", "4999990000000002");
        UUID threeDsMethod = createPaymentMethod(merchant, customerId, "three-ds-pm", "4999990000000008");
        UUID declinedIntent = createPaymentIntent(merchant, customerId, "declined-pi", 5600, "MANUAL");
        UUID threeDsIntent = createPaymentIntent(merchant, customerId, "three-ds-pi", 6700, "MANUAL");
        confirmPaymentIntent(merchant, declinedIntent, declinedMethod, "declined-confirm");
        confirmPaymentIntent(merchant, threeDsIntent, threeDsMethod, "three-ds-confirm");

        cancelPaymentIntent(merchant, declinedIntent, "declined-cancel", "req-declined-cancel");
        cancelPaymentIntent(merchant, threeDsIntent, "three-ds-cancel", "req-three-ds-cancel");

        assertThat(paymentIntentStatus(declinedIntent)).isEqualTo("CANCELED");
        assertThat(paymentIntentStatus(threeDsIntent)).isEqualTo("CANCELED");
        assertThat(countRows("authorization_voids")).isZero();
        assertThat(countAuditLogs("payment_intent.canceled")).isEqualTo(2);
    }

    @Test
    void terminalCapturedAndRefundedIntentsCannotBeCanceled() throws Exception {
        MerchantAccess merchant = createMerchant("terminal-cancel-merchant");
        UUID customerId = createCustomer(merchant, "terminal@example.com");
        UUID firstMethod = createPaymentMethod(merchant, customerId, "terminal-pm-a", "4999990000000001");
        UUID secondMethod = createPaymentMethod(merchant, customerId, "terminal-pm-b", "4999990000000001");
        UUID capturedIntent = createPaymentIntent(merchant, customerId, "captured-pi", 7800, "AUTOMATIC");
        UUID refundedIntent = createPaymentIntent(merchant, customerId, "refunded-pi", 8900, "AUTOMATIC");
        confirmPaymentIntent(merchant, capturedIntent, firstMethod, "captured-confirm");
        confirmPaymentIntent(merchant, refundedIntent, secondMethod, "refunded-confirm");
        refund(merchant, refundedIntent, 8900, "terminal-refund");

        cancelExpectingConflict(merchant, capturedIntent, "cancel-captured");
        cancelExpectingConflict(merchant, refundedIntent, "cancel-refunded");

        assertThat(paymentIntentStatus(capturedIntent)).isEqualTo("CAPTURED");
        assertThat(paymentIntentStatus(refundedIntent)).isEqualTo("REFUNDED");
        assertThat(countRows("authorization_voids")).isZero();
    }

    @Test
    void tenantAndIdempotencyOperationBoundariesProtectCancellation() throws Exception {
        MerchantAccess merchantA = createMerchant("tenant-a");
        MerchantAccess merchantB = createMerchant("tenant-b");
        UUID customerA = createCustomer(merchantA, "tenant-a@example.com");
        UUID intentA = createPaymentIntent(merchantA, customerA, "tenant-a-pi", 9100, "MANUAL");

        mockMvc.perform(post("/payment_intents/{id}/cancel", intentA)
                        .header(API_KEY, merchantB.apiKey())
                        .header(IDEMPOTENCY_KEY, "cross-merchant-cancel"))
                .andExpect(status().isNotFound());
        assertThat(paymentIntentStatus(intentA)).isEqualTo("REQUIRES_CONFIRMATION");

        cancelPaymentIntent(merchantA, intentA, "tenant-a-cancel", "req-tenant-a-cancel");
        UUID secondIntent = createPaymentIntent(merchantA, customerA, "tenant-a-second-pi", 9200, "MANUAL");
        mockMvc.perform(post("/payment_intents/{id}/cancel", secondIntent)
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "tenant-a-cancel"))
                .andExpect(status().isConflict());
        assertThat(paymentIntentStatus(secondIntent)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @Test
    void databaseRejectsMismatchedAndDuplicateAuthorizationVoidLineage() throws Exception {
        MerchantAccess merchant = createMerchant("constraint-merchant");
        UUID customerId = createCustomer(merchant, "constraint@example.com");
        UUID firstMethod = createPaymentMethod(merchant, customerId, "constraint-pm-a", "4999990000000001");
        UUID secondMethod = createPaymentMethod(merchant, customerId, "constraint-pm-b", "4999990000000001");
        UUID firstIntent = createPaymentIntent(merchant, customerId, "constraint-pi-a", 9300, "MANUAL");
        UUID secondIntent = createPaymentIntent(merchant, customerId, "constraint-pi-b", 9400, "MANUAL");
        JsonNode firstAttempt = confirmPaymentIntent(merchant, firstIntent, firstMethod, "constraint-confirm-a");
        confirmPaymentIntent(merchant, secondIntent, secondMethod, "constraint-confirm-b");
        UUID firstAttemptId = UUID.fromString(firstAttempt.get("id").asText());
        UUID firstAuthorizationId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM authorizations
                WHERE payment_attempt_id = ?
                """, UUID.class, firstAttemptId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO authorization_voids (
                    id, merchant_id, authorization_id, payment_attempt_id, payment_intent_id,
                    status, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 'RECORDED', 'MERCHANT_CANCELED', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchant.id(), firstAuthorizationId, firstAttemptId, secondIntent))
                .isInstanceOf(DataIntegrityViolationException.class);

        cancelPaymentIntent(merchant, firstIntent, "constraint-cancel", "req-constraint-cancel");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO authorization_voids (
                    id, merchant_id, authorization_id, payment_attempt_id, payment_intent_id,
                    status, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 'RECORDED', 'MERCHANT_CANCELED', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchant.id(), firstAuthorizationId, firstAttemptId, firstIntent))
                .isInstanceOf(DataIntegrityViolationException.class);
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
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isCreated());
    }

    private JsonNode confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(REQUEST_ID, "req-" + idempotencyKey)
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

    private JsonNode cancelPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey,
            String requestId) throws Exception {
        String response = mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(REQUEST_ID, requestId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void cancelExpectingConflict(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().isConflict());
    }

    private JsonNode getAuthorization(MerchantAccess merchant, UUID paymentAttemptId) throws Exception {
        String response = mockMvc.perform(get("/payment_attempts/{id}/authorization", paymentAttemptId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void refund(MerchantAccess merchant, UUID paymentIntentId, long amount, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": %d
                                }
                                """.formatted(paymentIntentId, amount)))
                .andExpect(status().isCreated());
    }

    private String paymentIntentStatus(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
    }

    private long captureLedgerBalance(UUID paymentIntentId) {
        Long balance = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                """, Long.class, paymentIntentId);
        return balance == null ? Long.MIN_VALUE : balance;
    }

    private JsonNode auditAfter(String action) throws Exception {
        String value = jdbcTemplate.queryForObject("""
                SELECT after_json
                FROM audit_logs
                WHERE action = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, String.class, action);
        return objectMapper.readTree(value);
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

    private int countAuditLogs(String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = ?
                """, Integer.class, action);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
