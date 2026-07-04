package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PaymentIntentCaptureCancelPostgreSqlConcurrencyTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void concurrentCaptureAndCancelCommitExactlyOneConsistentTerminalOutcome() throws Exception {
        MerchantAccess merchant = createMerchant("capture-cancel-race-merchant");
        UUID customerId = createCustomer(merchant, "capture-cancel-race@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        UUID paymentIntentId = createPaymentIntent(merchant, customerId);
        createWebhookEndpoint(merchant);
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> capture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return performCapture(merchant, paymentIntentId);
            });
            Future<MvcResult> cancel = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return performCancel(merchant, paymentIntentId);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult captureResult = capture.get(10, TimeUnit.SECONDS);
            MvcResult cancelResult = cancel.get(10, TimeUnit.SECONDS);
            assertThat(List.of(captureResult.getResponse().getStatus(), cancelResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);

            String finalStatus = paymentIntentStatus(paymentIntentId);
            assertThat(finalStatus).isIn("CAPTURED", "CANCELED");
            assertThat(countRaceIdempotencyRecords()).isEqualTo(1);
            assertThat(countRows("authorizations")).isEqualTo(1);

            if ("CAPTURED".equals(finalStatus)) {
                assertThat(countCaptureLedgerEntries(paymentIntentId)).isEqualTo(2);
                assertThat(captureLedgerBalance(paymentIntentId)).isZero();
                assertThat(countRows("authorization_voids")).isZero();
                assertThat(countAuditLogs("payment_intent.captured")).isEqualTo(1);
                assertThat(countAuditLogs("payment_intent.canceled")).isZero();
                assertThat(countWebhookDeliveries("payment_intent.captured")).isEqualTo(1);
                assertThat(countWebhookDeliveries("payment_intent.canceled")).isZero();
            } else {
                assertThat(countCaptureLedgerEntries(paymentIntentId)).isZero();
                assertThat(countRows("authorization_voids")).isEqualTo(1);
                assertThat(countAuditLogs("payment_intent.captured")).isZero();
                assertThat(countAuditLogs("payment_intent.canceled")).isEqualTo(1);
                assertThat(countWebhookDeliveries("payment_intent.captured")).isZero();
                assertThat(countWebhookDeliveries("payment_intent.canceled")).isEqualTo(1);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
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
        return new MerchantAccess(json.get("apiKey").asText());
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

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId) throws Exception {
        String response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-cancel-race-payment-method")
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

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId) throws Exception {
        String response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-cancel-race-payment-intent")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 4200,
                                  "currency": "USD",
                                  "captureMethod": "MANUAL"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void createWebhookEndpoint(MerchantAccess merchant) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .header("Idempotency-Key", "webhook-" + merchant.apiKey().hashCode())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://capture-cancel-race.example/webhooks"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private void confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-cancel-race-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isCreated());
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CAPTURE");
    }

    private MvcResult performCapture(MerchantAccess merchant, UUID paymentIntentId) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/capture", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-cancel-race-capture"))
                .andReturn();
    }

    private MvcResult performCancel(MerchantAccess merchant, UUID paymentIntentId) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "capture-cancel-race-cancel"))
                .andReturn();
    }

    private String paymentIntentStatus(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
    }

    private int countCaptureLedgerEntries(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND source_id = ?
                """, Integer.class, paymentIntentId);
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

    private int countRaceIdempotencyRecords() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE idempotency_key IN ('capture-cancel-race-capture', 'capture-cancel-race-cancel')
                """, Integer.class);
    }

    private int countAuditLogs(String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = ?
                """, Integer.class, action);
    }

    private int countWebhookDeliveries(String eventType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = ?
                """, Integer.class, eventType);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record MerchantAccess(String apiKey) {
    }
}
