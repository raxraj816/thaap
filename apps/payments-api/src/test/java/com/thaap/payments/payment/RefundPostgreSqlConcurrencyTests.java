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
class RefundPostgreSqlConcurrencyTests {

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
    void concurrentRefundsCommitExactlyOneCoherentRefundTransaction() throws Exception {
        MerchantAccess merchant = createMerchant();
        UUID customerId = createCustomer(merchant);
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        UUID paymentIntentId = createPaymentIntent(merchant, customerId);
        createWebhookEndpoint(merchant);
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId);
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("CAPTURED");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> first = executor.submit(() -> performRefund(
                    merchant, paymentIntentId, "refund-race-first", ready, start));
            Future<MvcResult> second = executor.submit(() -> performRefund(
                    merchant, paymentIntentId, "refund-race-second", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(201, 409);

            UUID refundId = jdbcTemplate.queryForObject("SELECT id FROM refunds", UUID.class);
            assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REFUNDED");
            assertThat(countRows("refunds")).isEqualTo(1);
            assertThat(countLedgerEntries("PAYMENT_INTENT_CAPTURE")).isEqualTo(2);
            assertThat(countLedgerEntries("REFUND")).isEqualTo(2);
            assertThat(refundLedgerBalance(refundId)).isZero();
            assertThat(countRows("authorizations")).isEqualTo(1);
            assertThat(countAuditLogs("refund.created")).isEqualTo(1);
            assertThat(countWebhookDeliveries("refund.created", refundId)).isEqualTo(1);
            assertThat(countRefundRaceIdempotencyRecords()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private MerchantAccess createMerchant() throws Exception {
        String response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "refund-race-merchant"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new MerchantAccess(objectMapper.readTree(response).get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant) throws Exception {
        String response = mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "refund-race@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId) throws Exception {
        String response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "refund-race-method")
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
                        .header(IDEMPOTENCY_KEY, "refund-race-intent")
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
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void createWebhookEndpoint(MerchantAccess merchant) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .header("Idempotency-Key", "webhook-" + merchant.apiKey().hashCode())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://refund-race.example/webhooks"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private void confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "refund-race-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isCreated());
    }

    private MvcResult performRefund(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(post("/refunds")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 4200
                                }
                                """.formatted(paymentIntentId)))
                .andReturn();
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

    private long refundLedgerBalance(UUID refundId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries
                WHERE source_type = 'REFUND'
                  AND refund_id = ?
                """, Long.class, refundId);
    }

    private int countAuditLogs(String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = ?
                """, Integer.class, action);
    }

    private int countWebhookDeliveries(String eventType, UUID resourceId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = ?
                  AND resource_id = ?
                """, Integer.class, eventType, resourceId);
    }

    private int countRefundRaceIdempotencyRecords() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE idempotency_key IN ('refund-race-first', 'refund-race-second')
                  AND status = 'COMPLETED'
                """, Integer.class);
    }

    private record MerchantAccess(String apiKey) {
    }
}
