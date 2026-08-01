package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.TransactionalDomainEventWriter;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentDomainEventRollbackTests {

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
    private TransactionalDomainEventWriter eventWriter;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
        reset(eventWriterTarget());
    }

    @Test
    void intentEventFailureRollsBackIntentStreamPublicationAndIdempotencyTruth() throws Exception {
        MerchantAccess merchant = createMerchant("event-create-rollback");
        UUID customerId = createCustomer(merchant, "event-create-rollback@example.com");
        failEvent(DomainEventType.PAYMENT_INTENT_CREATED);

        mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "event-create-rollback")
                        .contentType(APPLICATION_JSON)
                        .content(paymentIntentBody(customerId, 1_200L, "MANUAL")))
                .andExpect(status().isInternalServerError());

        assertThat(countRows("payment_intents")).isZero();
        assertThat(countRows("domain_events")).isZero();
        assertThat(countRows("event_publications")).isZero();
        assertThat(countRows("event_stream_heads")).isZero();
        assertThat(countIdempotencyRecords("event-create-rollback")).isZero();
    }

    @Test
    void decisionEventFailureRollsBackConfirmLineageAndLeavesOnlyIntentFact() throws Exception {
        PaymentFixture fixture = createFixture("event-confirm-rollback", "MANUAL");
        failEvent(DomainEventType.FRAUD_DECISION_CREATED);

        confirm(fixture, "event-confirm-rollback", 500);

        assertThat(paymentIntentStatus(fixture.paymentIntentId())).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countEvents(fixture.paymentIntentId())).isEqualTo(1);
        assertThat(eventTypes(fixture.paymentIntentId())).containsExactly("payment_intent.created");
        assertThat(countIdempotencyRecords("event-confirm-rollback")).isZero();
    }

    @Test
    void captureEventFailureRollsBackMoneyStateAndPreservesPreCaptureStream() throws Exception {
        PaymentFixture fixture = createFixture("event-capture-rollback", "MANUAL");
        confirm(fixture, "event-capture-confirm", 201);
        assertThat(eventTypes(fixture.paymentIntentId())).containsExactly(
                "payment_intent.created", "fraud_decision.created",
                "authorization.created", "payment_attempt.completed");
        failEvent(DomainEventType.CAPTURE_CREATED);

        mockMvc.perform(post("/payment_intents/{id}/capture", fixture.paymentIntentId())
                        .header(API_KEY, fixture.merchant().apiKey())
                        .header(IDEMPOTENCY_KEY, "event-capture-rollback"))
                .andExpect(status().isInternalServerError());

        assertThat(paymentIntentStatus(fixture.paymentIntentId())).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countEvents(fixture.paymentIntentId())).isEqualTo(4);
        assertThat(eventTypes(fixture.paymentIntentId())).doesNotContain("capture.created");
        assertThat(countIdempotencyRecords("event-capture-rollback")).isZero();
    }

    @Test
    void refundEventFailureRollsBackRefundAndLeavesCapturedMoneyTruthUnchanged() throws Exception {
        PaymentFixture fixture = createFixture("event-refund-rollback", "AUTOMATIC");
        confirm(fixture, "event-refund-confirm", 201);
        assertThat(paymentIntentStatus(fixture.paymentIntentId())).isEqualTo("CAPTURED");
        assertThat(countEvents(fixture.paymentIntentId())).isEqualTo(5);
        failEvent(DomainEventType.REFUND_CREATED);

        mockMvc.perform(post("/refunds")
                        .header(API_KEY, fixture.merchant().apiKey())
                        .header(IDEMPOTENCY_KEY, "event-refund-rollback")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentIntentId": "%s",
                                  "amount": 1200
                                }
                                """.formatted(fixture.paymentIntentId())))
                .andExpect(status().isInternalServerError());

        assertThat(paymentIntentStatus(fixture.paymentIntentId())).isEqualTo("CAPTURED");
        assertThat(countRows("refunds")).isZero();
        assertThat(countLedgerEntries("REFUND")).isZero();
        assertThat(countLedgerEntries("PAYMENT_INTENT_CAPTURE")).isEqualTo(2);
        assertThat(countEvents(fixture.paymentIntentId())).isEqualTo(5);
        assertThat(eventTypes(fixture.paymentIntentId())).doesNotContain("refund.created");
        assertThat(countIdempotencyRecords("event-refund-rollback")).isZero();
    }

    @Test
    void cancellationEventFailureRollsBackPriorVoidEventAndAuthorizationVoidState() throws Exception {
        PaymentFixture fixture = createFixture("event-cancel-rollback", "MANUAL");
        confirm(fixture, "event-cancel-confirm", 201);
        failEvent(DomainEventType.PAYMENT_INTENT_CANCELED);

        mockMvc.perform(post("/payment_intents/{id}/cancel", fixture.paymentIntentId())
                        .header(API_KEY, fixture.merchant().apiKey())
                        .header(IDEMPOTENCY_KEY, "event-cancel-rollback"))
                .andExpect(status().isInternalServerError());

        assertThat(paymentIntentStatus(fixture.paymentIntentId())).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("authorization_voids")).isZero();
        assertThat(countEvents(fixture.paymentIntentId())).isEqualTo(4);
        assertThat(eventTypes(fixture.paymentIntentId()))
                .doesNotContain("authorization.voided", "payment_intent.canceled");
        assertThat(countIdempotencyRecords("event-cancel-rollback")).isZero();
    }

    private void failEvent(DomainEventType eventType) {
        doThrow(new IllegalStateException("injected domain event persistence failure"))
                .when(eventWriterTarget()).append(
                        argThat(command -> command.eventType() == eventType));
    }

    private TransactionalDomainEventWriter eventWriterTarget() {
        return AopTestUtils.getUltimateTargetObject(eventWriter);
    }

    private PaymentFixture createFixture(String suffix, String captureMethod) throws Exception {
        MerchantAccess merchant = createMerchant(suffix);
        UUID customerId = createCustomer(merchant, suffix + "@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, suffix + "-method");
        UUID paymentIntentId = createPaymentIntent(
                merchant, customerId, suffix + "-intent", captureMethod);
        return new PaymentFixture(merchant, paymentMethodId, paymentIntentId);
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
                .andReturn().getResponse().getContentAsString();
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
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId,
            String idempotencyKey) throws Exception {
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
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId,
            String idempotencyKey, String captureMethod) throws Exception {
        String response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(paymentIntentBody(customerId, 1_200L, captureMethod)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private String paymentIntentBody(UUID customerId, long amount, String captureMethod) {
        return """
                {
                  "customerId": "%s",
                  "amount": %d,
                  "currency": "USD",
                  "captureMethod": "%s"
                }
                """.formatted(customerId, amount, captureMethod);
    }

    private void confirm(PaymentFixture fixture, String idempotencyKey,
            int expectedStatus) throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", fixture.paymentIntentId())
                        .header(API_KEY, fixture.merchant().apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(fixture.paymentMethodId())))
                .andExpect(status().is(expectedStatus));
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

    private int countEvents(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM domain_events
                WHERE stream_type = 'PAYMENT' AND stream_id = ?
                """, Integer.class, paymentIntentId);
    }

    private java.util.List<String> eventTypes(UUID paymentIntentId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM domain_events
                WHERE stream_type = 'PAYMENT' AND stream_id = ?
                ORDER BY stream_version
                """, String.class, paymentIntentId);
    }

    private int countLedgerEntries(String sourceType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE source_type = ?
                """, Integer.class, sourceType);
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

    private record PaymentFixture(MerchantAccess merchant, UUID paymentMethodId,
            UUID paymentIntentId) {
    }
}
