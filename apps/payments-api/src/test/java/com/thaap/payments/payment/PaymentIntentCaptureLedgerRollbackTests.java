package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.ledger.LedgerEntryRepository;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentIntentCaptureLedgerRollbackTests {

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

    @MockitoBean
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
        reset(ledgerEntryRepository);
    }

    @Test
    void ledgerPersistenceFailureRollsBackCaptureStateWebhookAuditAndIdempotencyCompletion() throws Exception {
        when(ledgerEntryRepository.saveAll(anyList()))
                .thenThrow(new IllegalStateException("ledger store unavailable"));
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "capture-rollback-pm");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "capture-rollback-pi", 1200);
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "capture-rollback-confirm");

        mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-ledger-failure"))
                .andExpect(status().isInternalServerError());

        verify(ledgerEntryRepository, times(1)).saveAll(anyList());
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countWebhookDeliveries("payment_intent.captured")).isZero();
        assertThat(countAuditLogsByAction("payment_intent.captured")).isZero();
        assertThat(countIdempotencyRecords("capture-ledger-failure")).isZero();
    }

    @Test
    void ledgerPersistenceFailureRollsBackEntireAutomaticConfirmTransaction() throws Exception {
        when(ledgerEntryRepository.saveAll(anyList()))
                .thenThrow(new IllegalStateException("ledger store unavailable"));
        MerchantAccess merchant = createMerchant("automatic-merchant");
        UUID customerId = createCustomer(merchant, "automatic@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "automatic-rollback-pm");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "automatic-rollback-pi", 2300,
                "AUTOMATIC");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "automatic-confirm-ledger-failure")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isInternalServerError());

        verify(ledgerEntryRepository, times(1)).saveAll(anyList());
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countRows("audit_logs")).isZero();
        assertThat(countWebhookDeliveries("payment_attempt.created")).isZero();
        assertThat(countWebhookDeliveries("payment_intent.captured")).isZero();
        assertThat(countIdempotencyRecords("automatic-confirm-ledger-failure")).isZero();
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
        return new MerchantAccess(objectMapper.readTree(response).get("apiKey").asText());
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

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey)
            throws Exception {
        String response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
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
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        return createPaymentIntent(merchant, customerId, idempotencyKey, amount, "MANUAL");
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

    private int countIdempotencyRecords(String idempotencyKey) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE idempotency_key = ?
                """, Integer.class, idempotencyKey);
    }

    private record MerchantAccess(String apiKey) {
    }
}
