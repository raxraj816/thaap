package com.thaap.payments.postauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerEntryRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import com.thaap.payments.webhook.WebhookEndpointRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostAuthorizationContractTests {
    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private MockMvc mockMvc;
    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @MockitoSpyBean private AuditLogService auditLogService;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntryRepository;
    @MockitoSpyBean private WebhookEndpointRepository webhookEndpointRepository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        reset(auditLogService);
        reset(ledgerEntryRepository);
        reset(webhookEndpointRepository);
        databaseCleaner.clean();
    }

    @Test
    void recommendationsNeverMoveMoneyAndOnlyReviewOpensOnePostAuthorizationCase() throws Exception {
        Merchant merchant = createMerchant("post-authorization-recommendations");
        CapturedPayment noAction = createCapturedPayment(merchant, "no-action");
        CapturedPayment refundRecommendation = createCapturedPayment(merchant, "refund-recommendation");
        CapturedPayment reviewRecommendation = createCapturedPayment(merchant, "review-recommendation");

        JsonNode noActionSignal = signal(merchant, noAction.paymentIntentId(), "signal-no-action",
                "NO_ACTION", "MERCHANT_RISK_ALERT", DatabaseInstant.now());
        JsonNode refundSignal = signal(merchant, refundRecommendation.paymentIntentId(), "signal-refund",
                "REFUND", "CUSTOMER_REPORTED_FRAUD", DatabaseInstant.now());
        JsonNode reviewSignal = signal(merchant, reviewRecommendation.paymentIntentId(), "signal-review",
                "REVIEW", "ACCOUNT_TAKEOVER_INDICATOR", DatabaseInstant.now());

        assertThat(noActionSignal.get("actionTaken").asText()).isEqualTo("NONE");
        assertThat(refundSignal.get("actionTaken").asText()).isEqualTo("NONE");
        assertThat(reviewSignal.get("actionTaken").asText()).isEqualTo("REVIEW_OPENED");
        assertThat(reviewSignal.get("reviewCaseId").isNull()).isFalse();
        assertThat(count("post_authorization_signals")).isEqualTo(3);
        assertThat(count("manual_review_cases")).isOne();
        assertThat(count("refunds")).isZero();
        assertThat(count("fraud_labels")).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT status FROM payment_intents", String.class))
                .containsOnly("CAPTURED");
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, reviewRecommendation.fraudDecisionId())).isEqualTo("ALLOW");
    }

    @Test
    void laterReviewCannotRewriteEarlierNoActionSignalResponseLineage() throws Exception {
        Merchant merchant = createMerchant("post-authorization-stable-lineage");
        CapturedPayment payment = createCapturedPayment(merchant, "stable-lineage");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode noAction = signal(merchant, payment.paymentIntentId(), "stable-no-action",
                "NO_ACTION", "MERCHANT_RISK_ALERT", evidenceTime);
        JsonNode review = signal(merchant, payment.paymentIntentId(), "later-review",
                "REVIEW", "ACCOUNT_TAKEOVER_INDICATOR", evidenceTime);
        String noActionBody = signalBody(payment.paymentIntentId(), "NO_ACTION",
                "MERCHANT_RISK_ALERT", evidenceTime)
                .replace("strict-event", "stable-no-action-external");

        JsonNode externalReplay = objectMapper.readTree(mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "stable-no-action-different-request")
                        .contentType(APPLICATION_JSON).content(noActionBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode read = objectMapper.readTree(mockMvc.perform(get("/post_authorization_signals/{id}",
                        noAction.get("id").asText()).header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(review.get("actionTaken").asText()).isEqualTo("REVIEW_OPENED");
        assertThat(externalReplay.get("actionTaken").asText()).isEqualTo("NONE");
        assertThat(externalReplay.get("reviewCaseId").isNull()).isTrue();
        assertThat(read.get("actionTaken").asText()).isEqualTo("NONE");
        assertThat(read.get("reviewCaseId").isNull()).isTrue();
        assertThat(read.get("id")).isEqualTo(noAction.get("id"));
    }

    @Test
    void riskManagerRefundAndReportFraudCreatesOneBalancedReusableTruth() throws Exception {
        Merchant merchant = createMerchant("post-authorization-manager-refund");
        CapturedPayment payment = createCapturedPayment(merchant, "manager-refund");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "manager-refund-signal",
                "REVIEW", "ACCOUNT_TAKEOVER_INDICATOR", evidenceTime);
        Operator manager = createOperator(merchant, "manager-refund-operator", "RISK_MANAGER");

        String body = """
                {
                  "outcome":"REFUND_AND_REPORT_FRAUD",
                  "classification":"CONFIRMED_FRAUD",
                  "fraudType":"ACCOUNT_TAKEOVER",
                  "reasonCode":"ACCOUNT_TAKEOVER_CONFIRMED",
                  "evidenceOccurredAt":"%s"
                }
                """.formatted(evidenceTime);
        String first = mockMvc.perform(post("/risk/reviews/{id}/resolve", signal.get("reviewCaseId").asText())
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-refund-resolve")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/risk/reviews/{id}/resolve", signal.get("reviewCaseId").asText())
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-refund-resolve")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(first);

        assertThat(objectMapper.readTree(replay)).isEqualTo(response);
        assertThat(response.get("paymentIntentStatus").asText()).isEqualTo("REFUNDED");
        assertThat(response.get("refundReasonCode").asText())
                .isEqualTo("CONFIRMED_FRAUD_CUSTOMER_PROTECTION");
        assertThat(response.get("refundActionResult").asText()).isEqualTo("CREATED");
        assertThat(response.get("refundActionReasonCode").asText())
                .isEqualTo("CONFIRMED_FRAUD_CUSTOMER_PROTECTION");
        assertThat(response.get("label").get("fraudType").asText()).isEqualTo("ACCOUNT_TAKEOVER");
        assertThat(count("refunds")).isOne();
        assertThat(count("review_refund_actions")).isOne();
        assertThat(count("fraud_labels")).isOne();
        assertThat(count("review_resolutions")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT source FROM fraud_labels", String.class))
                .isEqualTo("POST_AUTHORIZATION_SIGNAL");
        assertThat(jdbcTemplate.queryForObject("SELECT evidence_occurred_at FROM fraud_labels",
                Instant.class)).isEqualTo(evidenceTime);
        assertThat(jdbcTemplate.queryForObject("SELECT action_result FROM review_refund_actions",
                String.class)).isEqualTo("CREATED");
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, payment.fraudDecisionId())).isEqualTo("ALLOW");
    }

    @Test
    void analystCannotRefundButCanResolveAssignedNoActionWithoutLabel() throws Exception {
        Merchant merchant = createMerchant("post-authorization-role-matrix");
        CapturedPayment payment = createCapturedPayment(merchant, "role-matrix");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "role-matrix-signal",
                "REVIEW", "MERCHANT_RISK_ALERT", evidenceTime);
        Operator analyst = createOperator(merchant, "role-matrix-analyst", "ANALYST");
        String reviewId = signal.get("reviewCaseId").asText();
        mockMvc.perform(post("/risk/reviews/{id}/assign", reviewId)
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "role-matrix-assign")
                        .contentType(APPLICATION_JSON).content("{\"expectedAssignmentVersion\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", reviewId)
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-refund-denied")
                        .contentType(APPLICATION_JSON).content("""
                                {"outcome":"REFUND","classification":"INCONCLUSIVE",
                                 "reasonCode":"POST_AUTHORIZATION_REFUND","evidenceOccurredAt":"%s"}
                                """.formatted(evidenceTime)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", reviewId)
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-no-action")
                        .contentType(APPLICATION_JSON).content("""
                                {"outcome":"NO_ACTION","classification":"INCONCLUSIVE",
                                 "reasonCode":"POST_AUTHORIZATION_NO_ACTION","evidenceOccurredAt":"%s"}
                                """.formatted(evidenceTime)))
                .andExpect(status().isOk());
        assertThat(count("refunds")).isZero();
        assertThat(count("fraud_labels")).isZero();
        assertThat(count("review_resolutions")).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, payment.paymentIntentId())).isEqualTo("CAPTURED");
    }

    @Test
    void exactMerchantRefundIsReusedWithoutDuplicatingRefundOrLedger() throws Exception {
        Merchant merchant = createMerchant("post-authorization-refund-reuse");
        CapturedPayment payment = createCapturedPayment(merchant, "refund-reuse");
        mockMvc.perform(post("/refunds").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "merchant-refund-first").contentType(APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + payment.paymentIntentId()
                                + "\",\"amount\":4200}"))
                .andExpect(status().isCreated());
        int ledgerBefore = count("ledger_entries");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "refund-reuse-signal",
                "REVIEW", "MERCHANT_RISK_ALERT", evidenceTime);
        Operator manager = createOperator(merchant, "refund-reuse-manager", "RISK_MANAGER");
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/reviews/{id}/resolve",
                        signal.get("reviewCaseId").asText()).header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "refund-reuse-resolve").contentType(APPLICATION_JSON)
                        .content("""
                                {"outcome":"REFUND","classification":"INCONCLUSIVE",
                                 "reasonCode":"POST_AUTHORIZATION_REFUND","evidenceOccurredAt":"%s"}
                                """.formatted(evidenceTime)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.get("refundReasonCode").asText()).isEqualTo("MERCHANT_REQUESTED");
        assertThat(response.get("refundActionResult").asText()).isEqualTo("REUSED");
        assertThat(response.get("refundActionReasonCode").asText()).isEqualTo("RISK_REVIEW_REMEDIATION");
        assertThat(count("refunds")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(ledgerBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT action_result FROM review_refund_actions",
                String.class)).isEqualTo("REUSED");
    }

    @Test
    void signalIdentityStrictGrammarTenantAndChangedBodyAreClosed() throws Exception {
        Merchant owner = createMerchant("post-authorization-owner");
        Merchant foreign = createMerchant("post-authorization-foreign");
        CapturedPayment payment = createCapturedPayment(owner, "strict");
        Instant evidenceTime = DatabaseInstant.now();
        String body = signalBody(payment.paymentIntentId(), "REVIEW", "CARD_TESTING_INDICATOR", evidenceTime);
        String first = mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "strict-signal-key")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "strict-signal-key")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));
        mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "strict-signal-key")
                        .contentType(APPLICATION_JSON).content(body.replace("REVIEW", "REFUND")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, foreign.apiKey()).header(IDEMPOTENCY_KEY, "foreign-signal")
                        .contentType(APPLICATION_JSON).content(body.replace("strict-event", "foreign-event")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "bad-event-key")
                        .contentType(APPLICATION_JSON).content(body.replace("strict-event", "bad key")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "unknown-field")
                        .contentType(APPLICATION_JSON).content(body.substring(0, body.length() - 1)
                                + ",\"freeText\":\"do not persist\"}"))
                .andExpect(status().isBadRequest());
        assertThat(count("post_authorization_signals")).isOne();
        assertThat(count("manual_review_cases")).isOne();
    }

    @Test
    void outOfOrderSignalArrivalPreservesBothFactsWhileInvalidTimePartitionsAreRejected() throws Exception {
        Merchant merchant = createMerchant("post-authorization-time-order");
        CapturedPayment payment = createCapturedPayment(merchant, "time-order");
        Instant authorizationTime = jdbcTemplate.queryForObject("""
                SELECT a.created_at FROM authorizations a
                JOIN payment_attempts pa ON pa.id = a.payment_attempt_id
                WHERE pa.payment_intent_id = ?
                """, Instant.class, payment.paymentIntentId());
        Instant olderEvidence = authorizationTime.plusNanos(1_000);
        Instant newerEvidence = DatabaseInstant.now();

        signal(merchant, payment.paymentIntentId(), "newer-arrived-first",
                "NO_ACTION", "MERCHANT_RISK_ALERT", newerEvidence);
        signal(merchant, payment.paymentIntentId(), "older-arrived-second",
                "NO_ACTION", "CARD_TESTING_INDICATOR", olderEvidence);

        assertThat(jdbcTemplate.queryForList("""
                SELECT evidence_occurred_at FROM post_authorization_signals
                ORDER BY available_at
                """, Instant.class)).containsExactly(newerEvidence, olderEvidence);
        assertThat(count("manual_review_cases")).isZero();

        Instant future = DatabaseInstant.now().plusSeconds(60);
        mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "future-time")
                        .contentType(APPLICATION_JSON)
                        .content(signalBody(payment.paymentIntentId(), "NO_ACTION", "MERCHANT_RISK_ALERT", future)))
                .andExpect(status().isBadRequest());
        Instant predatesAuthorization = authorizationTime.minusNanos(1_000);
        mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "predates-authorization")
                        .contentType(APPLICATION_JSON)
                        .content(signalBody(payment.paymentIntentId(), "NO_ACTION", "MERCHANT_RISK_ALERT",
                                predatesAuthorization)))
                .andExpect(status().isBadRequest());
        assertThat(count("post_authorization_signals")).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalExternalEventsSerializeAndConvergeToOneSignalAndReview() throws Exception {
        Merchant merchant = createMerchant("post-authorization-concurrent-signal");
        CapturedPayment payment = createCapturedPayment(merchant, "concurrent-signal");
        Instant evidenceTime = DatabaseInstant.now();
        String body = signalBody(payment.paymentIntentId(), "REVIEW", "CARD_TESTING_INDICATOR", evidenceTime)
                .replace("strict-event", "concurrent-external-event");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/post_authorization_signals")
                                .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "concurrent-key-1")
                                .contentType(APPLICATION_JSON).content(body))
                        .andReturn();
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/post_authorization_signals")
                                .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "concurrent-key-2")
                                .contentType(APPLICATION_JSON).content(body))
                        .andReturn();
            });
            start.countDown();
            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();
            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 201);
            assertThat(firstResult.getResponse().getContentAsString())
                    .isEqualTo(secondResult.getResponse().getContentAsString());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("post_authorization_signals")).isOne();
        assertThat(count("manual_review_cases")).isOne();
    }

    @Test
    void postAuthorizationOutcomeClassificationReasonTaxonomyRejectsEveryCrossFamilyShortcut()
            throws Exception {
        Merchant merchant = createMerchant("post-authorization-taxonomy");
        CapturedPayment payment = createCapturedPayment(merchant, "taxonomy");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "taxonomy-signal",
                "REVIEW", "CUSTOMER_REPORTED_FRAUD", evidenceTime);
        Operator manager = createOperator(merchant, "taxonomy-manager", "RISK_MANAGER");
        String reviewId = signal.get("reviewCaseId").asText();
        String[] invalidBodies = {
            "{\"outcome\":\"REFUND\",\"classification\":\"CONFIRMED_FRAUD\",\"fraudType\":\"ACCOUNT_TAKEOVER\",\"reasonCode\":\"ACCOUNT_TAKEOVER_CONFIRMED\",\"evidenceOccurredAt\":\"%s\"}",
            "{\"outcome\":\"REFUND_AND_REPORT_FRAUD\",\"classification\":\"INCONCLUSIVE\",\"reasonCode\":\"POST_AUTHORIZATION_REFUND\",\"evidenceOccurredAt\":\"%s\"}",
            "{\"outcome\":\"REFUND_AND_REPORT_FRAUD\",\"classification\":\"CONFIRMED_FRAUD\",\"fraudType\":\"CARD_TESTING\",\"reasonCode\":\"ACCOUNT_TAKEOVER_CONFIRMED\",\"evidenceOccurredAt\":\"%s\"}",
            "{\"outcome\":\"NO_ACTION\",\"classification\":\"CONFIRMED_FRAUD\",\"fraudType\":\"CARD_TESTING\",\"reasonCode\":\"POST_AUTHORIZATION_NO_ACTION\",\"evidenceOccurredAt\":\"%s\"}",
            "{\"outcome\":\"APPROVE\",\"classification\":\"CONFIRMED_LEGITIMATE\",\"reasonCode\":\"REVIEW_APPROVED\",\"evidenceOccurredAt\":\"%s\"}"
        };
        for (int index = 0; index < invalidBodies.length; index++) {
            mockMvc.perform(post("/risk/reviews/{id}/resolve", reviewId)
                            .header(RISK_KEY, manager.secret())
                            .header(IDEMPOTENCY_KEY, "taxonomy-invalid-" + index)
                            .contentType(APPLICATION_JSON).content(invalidBodies[index].formatted(evidenceTime)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("refunds")).isZero();
        assertThat(count("fraud_labels")).isZero();
    }

    @Test
    void postAuthorizationSignalRefundAndActionHistoryAreAppendOnlyAtPostgreSql() throws Exception {
        Merchant merchant = createMerchant("post-authorization-append-only");
        CapturedPayment payment = createCapturedPayment(merchant, "append-only");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "append-only-signal",
                "REVIEW", "CARD_TESTING_INDICATOR", evidenceTime);
        Operator manager = createOperator(merchant, "append-only-manager", "RISK_MANAGER");
        mockMvc.perform(post("/risk/reviews/{id}/resolve", signal.get("reviewCaseId").asText())
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "append-only-resolve")
                        .contentType(APPLICATION_JSON).content("""
                                {"outcome":"REFUND","classification":"INCONCLUSIVE",
                                 "reasonCode":"POST_AUTHORIZATION_REFUND","evidenceOccurredAt":"%s"}
                                """.formatted(evidenceTime)))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE post_authorization_signals SET recommendation = 'REFUND'"))
                .hasMessageContaining("Post-authorization history is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE refunds SET reason_code = 'MERCHANT_REQUESTED'"))
                .hasMessageContaining("refund history is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM review_refund_actions"))
                .hasMessageContaining("Post-authorization history is append-only");
        assertThat(jdbcTemplate.queryForObject("SELECT recommendation FROM post_authorization_signals",
                String.class)).isEqualTo("REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT reason_code FROM refunds", String.class))
                .isEqualTo("RISK_REVIEW_REMEDIATION");
    }

    @Test
    void resolutionFirstForcedInterleavingMakesMerchantRefundWaitThenConvergeWithoutDoubleMoney()
            throws Exception {
        Merchant merchant = createMerchant("post-authorization-resolution-race");
        CapturedPayment payment = createCapturedPayment(merchant, "resolution-race");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "resolution-race-signal",
                "REVIEW", "MERCHANT_RISK_ALERT", evidenceTime);
        Operator manager = createOperator(merchant, "resolution-race-manager", "RISK_MANAGER");
        CountDownLatch resolutionReachedAudit = new CountDownLatch(1);
        CountDownLatch allowResolutionCommit = new CountDownLatch(1);
        AtomicBoolean blockNextAudit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockNextAudit.compareAndSet(true, false)) {
                resolutionReachedAudit.countDown();
                assertThat(allowResolutionCommit.await(5, TimeUnit.SECONDS)).isTrue();
            }
            invocation.callRealMethod();
            return null;
        }).when(auditLogService).recordRiskOperatorAction(any(), anyString(), anyString(),
                anyString(), any(), anyString(), isNull(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> resolution = executor.submit(() -> mockMvc.perform(
                            post("/risk/reviews/{id}/resolve", signal.get("reviewCaseId").asText())
                                    .header(RISK_KEY, manager.secret())
                                    .header(IDEMPOTENCY_KEY, "resolution-race-resolve")
                                    .contentType(APPLICATION_JSON).content("""
                                            {"outcome":"REFUND","classification":"INCONCLUSIVE",
                                             "reasonCode":"POST_AUTHORIZATION_REFUND",
                                             "evidenceOccurredAt":"%s"}
                                            """.formatted(evidenceTime)))
                    .andReturn().getResponse().getStatus());
            assertThat(resolutionReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> merchantRefund = executor.submit(() -> mockMvc.perform(post("/refunds")
                            .header(API_KEY, merchant.apiKey())
                            .header(IDEMPOTENCY_KEY, "resolution-race-merchant-refund")
                            .contentType(APPLICATION_JSON).content("{\"paymentIntentId\":\""
                                    + payment.paymentIntentId() + "\",\"amount\":4200}"))
                    .andReturn().getResponse().getStatus());

            assertThat(awaitPostgreSqlLockWait()).isTrue();
            assertThat(merchantRefund.isDone()).isFalse();
            allowResolutionCommit.countDown();
            assertThat(resolution.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(merchantRefund.get(5, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            allowResolutionCommit.countDown();
            executor.shutdownNow();
        }
        assertThat(count("refunds")).isOne();
        assertThat(count("review_refund_actions")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, payment.paymentIntentId())).isEqualTo("REFUNDED");
    }

    @Test
    void merchantRefundFirstForcedInterleavingMakesResolutionWaitThenReuseExactTruth()
            throws Exception {
        Merchant merchant = createMerchant("post-authorization-merchant-race");
        CapturedPayment payment = createCapturedPayment(merchant, "merchant-race");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "merchant-race-signal",
                "REVIEW", "MERCHANT_RISK_ALERT", evidenceTime);
        Operator manager = createOperator(merchant, "merchant-race-manager", "RISK_MANAGER");
        CountDownLatch merchantReachedAudit = new CountDownLatch(1);
        CountDownLatch allowMerchantCommit = new CountDownLatch(1);
        AtomicBoolean blockNextAudit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockNextAudit.compareAndSet(true, false)) {
                merchantReachedAudit.countDown();
                assertThat(allowMerchantCommit.await(5, TimeUnit.SECONDS)).isTrue();
            }
            invocation.callRealMethod();
            return null;
        }).when(auditLogService).recordMerchantApiAction(any(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> merchantRefund = executor.submit(() -> mockMvc.perform(post("/refunds")
                            .header(API_KEY, merchant.apiKey())
                            .header(IDEMPOTENCY_KEY, "merchant-race-refund")
                            .contentType(APPLICATION_JSON).content("{\"paymentIntentId\":\""
                                    + payment.paymentIntentId() + "\",\"amount\":4200}"))
                    .andReturn().getResponse().getStatus());
            assertThat(merchantReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> resolution = executor.submit(() -> mockMvc.perform(
                            post("/risk/reviews/{id}/resolve", signal.get("reviewCaseId").asText())
                                    .header(RISK_KEY, manager.secret())
                                    .header(IDEMPOTENCY_KEY, "merchant-race-resolve")
                                    .contentType(APPLICATION_JSON).content("""
                                            {"outcome":"REFUND","classification":"INCONCLUSIVE",
                                             "reasonCode":"POST_AUTHORIZATION_REFUND",
                                             "evidenceOccurredAt":"%s"}
                                            """.formatted(evidenceTime)))
                    .andReturn().getResponse().getStatus());
            assertThat(awaitPostgreSqlLockWait()).isTrue();
            assertThat(resolution.isDone()).isFalse();
            allowMerchantCommit.countDown();
            assertThat(merchantRefund.get(5, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(resolution.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            allowMerchantCommit.countDown();
            executor.shutdownNow();
        }
        assertThat(count("refunds")).isOne();
        assertThat(count("review_refund_actions")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT action_result FROM review_refund_actions",
                String.class)).isEqualTo("REUSED");
        assertThat(jdbcTemplate.queryForObject("SELECT reason_code FROM refunds", String.class))
                .isEqualTo("MERCHANT_REQUESTED");
    }

    @Test
    void ledgerFailureRollsBackResolutionRefundActionLabelAuditWebhookAndPayment() throws Exception {
        Merchant merchant = createMerchant("post-authorization-ledger-rollback");
        CapturedPayment payment = createCapturedPayment(merchant, "ledger-rollback");
        Instant evidenceTime = DatabaseInstant.now();
        JsonNode signal = signal(merchant, payment.paymentIntentId(), "ledger-rollback-signal",
                "REVIEW", "CUSTOMER_REPORTED_FRAUD", evidenceTime);
        Operator manager = createOperator(merchant, "ledger-rollback-manager", "RISK_MANAGER");
        org.mockito.Mockito.doThrow(new IllegalStateException("injected review refund ledger failure"))
                .when(ledgerEntryRepository)
                .saveAll(org.mockito.ArgumentMatchers.<LedgerEntry>anyList());

        resolveRefundExpecting(manager, signal, evidenceTime, "ledger-rollback-resolve", 500);
        assertResolutionRollback(payment);
    }

    @Test
    void auditAndWebhookFailuresEachRollBackAllPostAuthorizationMoneyTruth() throws Exception {
        Merchant auditMerchant = createMerchant("post-authorization-audit-rollback");
        CapturedPayment auditPayment = createCapturedPayment(auditMerchant, "audit-rollback");
        Instant auditEvidence = DatabaseInstant.now();
        JsonNode auditSignal = signal(auditMerchant, auditPayment.paymentIntentId(), "audit-rollback-signal",
                "REVIEW", "MERCHANT_RISK_ALERT", auditEvidence);
        Operator auditManager = createOperator(auditMerchant, "audit-rollback-manager", "RISK_MANAGER");
        org.mockito.Mockito.doThrow(new IllegalStateException("injected review audit failure"))
                .when(auditLogService).recordRiskOperatorAction(any(), anyString(), anyString(),
                        anyString(), any(), anyString(), isNull(), any());
        resolveRefundExpecting(auditManager, auditSignal, auditEvidence, "audit-rollback-resolve", 500);
        assertResolutionRollback(auditPayment);

        reset(auditLogService);
        databaseCleaner.clean();
        Merchant webhookMerchant = createMerchant("post-authorization-webhook-rollback");
        CapturedPayment webhookPayment = createCapturedPayment(webhookMerchant, "webhook-rollback");
        Instant webhookEvidence = DatabaseInstant.now();
        JsonNode webhookSignal = signal(webhookMerchant, webhookPayment.paymentIntentId(),
                "webhook-rollback-signal", "REVIEW", "MERCHANT_RISK_ALERT", webhookEvidence);
        Operator webhookManager = createOperator(webhookMerchant, "webhook-rollback-manager", "RISK_MANAGER");
        org.mockito.Mockito.doThrow(new IllegalStateException("injected review webhook failure"))
                .when(webhookEndpointRepository).findActiveSubscribedEndpointIds(any(), anyString());
        resolveRefundExpecting(webhookManager, webhookSignal, webhookEvidence,
                "webhook-rollback-resolve", 500);
        assertResolutionRollback(webhookPayment);
    }

    private void resolveRefundExpecting(Operator manager, JsonNode signal, Instant evidenceTime,
            String key, int expectedStatus) throws Exception {
        assertThat(mockMvc.perform(post("/risk/reviews/{id}/resolve", signal.get("reviewCaseId").asText())
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"outcome":"REFUND","classification":"INCONCLUSIVE",
                                 "reasonCode":"POST_AUTHORIZATION_REFUND",
                                 "evidenceOccurredAt":"%s"}
                                """.formatted(evidenceTime)))
                .andReturn().getResponse().getStatus()).isEqualTo(expectedStatus);
    }

    private void assertResolutionRollback(CapturedPayment payment) {
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("review_refund_actions")).isZero();
        assertThat(count("refunds")).isZero();
        assertThat(count("fraud_labels")).isZero();
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, payment.paymentIntentId())).isEqualTo("CAPTURED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases",
                String.class)).isEqualTo("OPEN");
    }

    private boolean awaitPostgreSqlLockWait() throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private JsonNode signal(Merchant merchant, UUID paymentIntentId, String key,
            String recommendation, String reason, Instant evidenceTime) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/post_authorization_signals")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content(signalBody(paymentIntentId, recommendation, reason, evidenceTime)
                                .replace("strict-event", key + "-external")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String signalBody(UUID paymentIntentId, String recommendation,
            String reason, Instant evidenceTime) {
        return """
                {"externalEventKey":"strict-event","paymentIntentId":"%s",
                 "recommendation":"%s","reasonCode":"%s",
                 "evidenceOccurredAt":"%s","receivedAt":"%s"}
                """.formatted(paymentIntentId, recommendation, reason, evidenceTime,
                evidenceTime);
    }

    private CapturedPayment createCapturedPayment(Merchant merchant, String key) throws Exception {
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + key + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key + "-method")
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\""
                                + customer.get("id").asText() + "\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key + "-intent")
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\""
                                + customer.get("id").asText()
                                + "\",\"amount\":4200,\"currency\":\"USD\",\"captureMethod\":\"AUTOMATIC\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode attempt = objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm",
                        intent.get("id").asText()).header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-confirm").contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + method.get("id").asText() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new CapturedPayment(UUID.fromString(intent.get("id").asText()),
                UUID.fromString(attempt.get("fraudDecisionId").asText()));
    }

    private Merchant createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Merchant(response.get("apiKey").asText());
    }

    private Operator createOperator(Merchant merchant, String key, String role) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Operator(response.get("credential").asText());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Merchant(String apiKey) {}
    private record Operator(String secret) {}
    private record CapturedPayment(UUID paymentIntentId, UUID fraudDecisionId) {}
}
