package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.audit.AuditLog;
import com.thaap.payments.audit.AuditLogRepository;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerEntryRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.webhook.WebhookDelivery;
import com.thaap.payments.webhook.WebhookDeliveryRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class RefundRollbackTests {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoSpyBean
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @MockitoSpyBean
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void refundLedgerFailureRollsBackEveryRefundSideEffect() throws Exception {
        RefundFixture fixture = createCapturedFixture("ledger");
        doThrow(new IllegalStateException("refund ledger store unavailable"))
                .when(ledgerEntryRepository).saveAll(org.mockito.ArgumentMatchers.<LedgerEntry>anyList());

        performFailingRefund(fixture, "refund-ledger-failure");

        assertRefundRolledBack(fixture.paymentIntentId(), "refund-ledger-failure");
    }

    @Test
    void refundWebhookFailureRollsBackEveryRefundSideEffect() throws Exception {
        RefundFixture fixture = createCapturedFixture("webhook");
        doThrow(new IllegalStateException("refund webhook store unavailable"))
                .when(webhookDeliveryRepository).save(any(WebhookDelivery.class));

        performFailingRefund(fixture, "refund-webhook-failure");

        assertRefundRolledBack(fixture.paymentIntentId(), "refund-webhook-failure");
    }

    @Test
    void refundAuditFailureRollsBackEveryRefundSideEffect() throws Exception {
        RefundFixture fixture = createCapturedFixture("audit");
        doThrow(new IllegalStateException("refund audit store unavailable"))
                .when(auditLogRepository).save(any(AuditLog.class));

        performFailingRefund(fixture, "refund-audit-failure");

        assertRefundRolledBack(fixture.paymentIntentId(), "refund-audit-failure");
    }

    private RefundFixture createCapturedFixture(String suffix) throws Exception {
        String merchantResponse = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "refund-rollback-%s"
                                }
                                """.formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String apiKey = objectMapper.readTree(merchantResponse).get("apiKey").asText();

        String customerResponse = mockMvc.perform(post("/customers")
                        .header(API_KEY, apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "refund-rollback-%s@example.com"
                                }
                                """.formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID customerId = UUID.fromString(objectMapper.readTree(customerResponse).get("id").asText());

        String methodResponse = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, apiKey)
                        .header(IDEMPOTENCY_KEY, "refund-rollback-method-" + suffix)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4999990000000001"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID paymentMethodId = UUID.fromString(objectMapper.readTree(methodResponse).get("id").asText());

        String intentResponse = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, apiKey)
                        .header(IDEMPOTENCY_KEY, "refund-rollback-intent-" + suffix)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 4200,
                                  "currency": "USD",
                                  "captureMethod": "AUTOMATIC"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID paymentIntentId = UUID.fromString(objectMapper.readTree(intentResponse).get("id").asText());

        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, apiKey)
                        .header("Idempotency-Key", "webhook-refund-rollback-" + suffix)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://refund-rollback-%s.example/webhooks"
                                }
                                """.formatted(suffix)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, apiKey)
                        .header(IDEMPOTENCY_KEY, "refund-rollback-confirm-" + suffix)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isCreated());
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");
        return new RefundFixture(apiKey, paymentIntentId);
    }

    private void performFailingRefund(RefundFixture fixture, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/refunds")
                        .header(API_KEY, fixture.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 4200
                                }
                                """.formatted(fixture.paymentIntentId())))
                .andExpect(status().isInternalServerError());
    }

    private void assertRefundRolledBack(UUID paymentIntentId, String idempotencyKey) {
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");
        assertThat(countRows("refunds")).isZero();
        assertThat(countRefundLedgerEntries()).isZero();
        assertThat(countRefundWebhookDeliveries()).isZero();
        assertThat(countRefundAuditLogs()).isZero();
        assertThat(countIdempotencyRecords(idempotencyKey)).isZero();
        assertThat(countCaptureLedgerEntries()).isEqualTo(2);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
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

    private int countRefundLedgerEntries() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                """, Integer.class);
    }

    private int countCaptureLedgerEntries() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                """, Integer.class);
    }

    private int countRefundWebhookDeliveries() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = 'refund.created'
                """, Integer.class);
    }

    private int countRefundAuditLogs() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = 'refund.created'
                """, Integer.class);
    }

    private int countIdempotencyRecords(String key) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE idempotency_key = ?
                """, Integer.class, key);
    }

    private record RefundFixture(String apiKey, UUID paymentIntentId) {
    }
}
