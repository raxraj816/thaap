package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.velocity.VelocityAssessment;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FeatureLineagePostgreSqlConcurrencyTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
    @Autowired
    private DataSource dataSource;
    @MockitoSpyBean
    private FeatureMaterializer featureMaterializer;
    @MockitoSpyBean
    private DeterministicConfirmDecisionEngine confirmDecisionEngine;
    @MockitoSpyBean
    private ProcessorAdapter processorAdapter;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        clearInvocations(featureMaterializer, confirmDecisionEngine, processorAdapter);
    }

    @Test
    void realFeatureFlushFailureRollsBackBeforeProcessorReleasesReservationAndAllowsOneRetry() throws Exception {
        ConfirmFixture fixture = createConfirmFixture("flush-failure");
        AtomicBoolean corruptNextMaterialization = new AtomicBoolean(true);
        doAnswer(invocation -> {
            MaterializedFeatureSet materialized = (MaterializedFeatureSet) invocation.callRealMethod();
            if (corruptNextMaterialization.compareAndSet(true, false)) {
                ReflectionTestUtils.setField(
                        materialized.values().getFirst(), "trafficOrigin", TrafficOrigin.SYNTHETIC_SCENARIO);
            }
            return materialized;
        }).when(featureMaterializer).materialize(
                any(PaymentIntent.class), any(PaymentAttempt.class), any(PaymentMethod.class),
                nullable(RiskContext.class), any(VelocityAssessment.class),
                any(com.thaap.payments.riskpolicy.MerchantRiskTier.class), any(Instant.class));

        MvcResult failed = performConfirm(fixture, "confirm-flush-failure");

        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        verify(processorAdapter, times(0)).authorize(any(ProcessorAuthorizationRequest.class));
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("feature_values")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                WHERE merchant_id = ? AND idempotency_key = 'confirm-flush-failure'
                """, Integer.class, fixture.merchantId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payment_intents WHERE id = ?
                """, String.class, fixture.paymentIntentId())).isEqualTo("REQUIRES_CONFIRMATION");

        MvcResult retry = performConfirm(fixture, "confirm-flush-failure");

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        verify(processorAdapter, times(1)).authorize(any(ProcessorAuthorizationRequest.class));
        assertCompleteSingleLineage();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM idempotency_records
                WHERE merchant_id = ? AND idempotency_key = 'confirm-flush-failure'
                """, String.class, fixture.merchantId())).isEqualTo("COMPLETED");
    }

    @Test
    void realRuleResultFlushFailureRollsBackBeforeProcessorReleasesReservationAndAllowsOneRetry() throws Exception {
        ConfirmFixture fixture = createConfirmFixture("rule-flush-failure");
        AtomicBoolean corruptNextDecision = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ConfirmDecision decision = (ConfirmDecision) invocation.callRealMethod();
            if (!corruptNextDecision.compareAndSet(true, false)) {
                return decision;
            }
            List<RuleEvaluation> corruptedEvaluations = decision.ruleEvaluations().stream()
                    .map(evaluation -> "BLOCK_USD_EXTREME_AMOUNT_V1".equals(evaluation.ruleCode())
                            ? new RuleEvaluation(evaluation.ruleId(), evaluation.ruleCode(), evaluation.ruleVersion(),
                                    evaluation.priority(), evaluation.matched(), FraudDecisionAction.REVIEW,
                                    evaluation.reasonCode(), evaluation.outcome(), evaluation.latencyUs())
                            : evaluation)
                    .toList();
            return new ConfirmDecision(decision.action(), decision.riskScore(), decision.riskLevel(),
                    decision.rulesetVersion(), decision.modelVersion(), decision.reasonCodes(),
                    corruptedEvaluations);
        }).when(confirmDecisionEngine).decide(any(DecisionFeatureContext.class));

        MvcResult failed = performConfirm(fixture, "confirm-rule-flush-failure");

        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        verify(processorAdapter, times(0)).authorize(any(ProcessorAuthorizationRequest.class));
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("feature_values")).isZero();
        assertThat(countRows("rule_execution_results")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                WHERE merchant_id = ? AND idempotency_key = 'confirm-rule-flush-failure'
                """, Integer.class, fixture.merchantId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payment_intents WHERE id = ?
                """, String.class, fixture.paymentIntentId())).isEqualTo("REQUIRES_CONFIRMATION");

        MvcResult retry = performConfirm(fixture, "confirm-rule-flush-failure");

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        verify(processorAdapter, times(1)).authorize(any(ProcessorAuthorizationRequest.class));
        assertCompleteSingleLineage();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM idempotency_records
                WHERE merchant_id = ? AND idempotency_key = 'confirm-rule-flush-failure'
                """, String.class, fixture.merchantId())).isEqualTo("COMPLETED");
    }

    @Test
    void distinctKeysWaitAtFeatureFlushAndPaymentIntentLockThenCommitOneLineage() throws Exception {
        ConfirmFixture fixture = createConfirmFixture("distinct-key");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE feature_values IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> first = executor.submit(() -> performConfirm(fixture, "confirm-distinct-a"));
            awaitBlockedQuery("feature_values", 1);
            assertThat(countRows("authorizations")).isZero();

            Future<MvcResult> second = executor.submit(() -> performConfirm(fixture, "confirm-distinct-b"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("feature_values"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("payment_intents"));

            blocker.commit();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertCompleteSingleLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyWaitsBehindLiveOwnerThenReturnsExactCompletedReplayWithoutReevaluation() throws Exception {
        ConfirmFixture fixture = createConfirmFixture("same-key");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE feature_values IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> owner = executor.submit(() -> performConfirm(fixture, "confirm-same"));
            awaitBlockedQuery("feature_values", 1);
            Future<MvcResult> concurrentReplay = executor.submit(() -> performConfirm(fixture, "confirm-same"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("feature_values"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("idempotency_records"));
            assertThat(countRows("authorizations")).isZero();

            blocker.commit();
            MvcResult original = owner.get(10, TimeUnit.SECONDS);
            assertThat(original.getResponse().getStatus()).isEqualTo(201);
            MvcResult replay = concurrentReplay.get(10, TimeUnit.SECONDS);
            assertThat(replay.getResponse().getStatus()).isEqualTo(201);
            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(original.getResponse().getContentAsString());
            assertCompleteSingleLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fraudBlockDistinctKeysSerializeAtRuleResultSqlAndCommitOneTerminalDecision() throws Exception {
        ConfirmFixture fixture = createConfirmFixture("confirmation_reservation-block-distinct", 5_000_000L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE rule_execution_results IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> first = executor.submit(() -> performConfirm(fixture, "block-distinct-a"));
            awaitBlockedQuery("rule_execution_results", 1);
            assertThat(countRows("authorizations")).isZero();

            Future<MvcResult> second = executor.submit(() -> performConfirm(fixture, "block-distinct-b"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("rule_execution_results"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("payment_intents"));

            blocker.commit();
            MvcResult winner = first.get(10, TimeUnit.SECONDS);
            assertThat(winner.getResponse().getStatus()).isEqualTo(201);
            assertThat(objectMapper.readTree(winner.getResponse().getContentAsString()).get("status").asText())
                    .isEqualTo("BLOCKED");
            assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertCompleteBlockedLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fraudBlockSameKeyWaitsAtRuleResultSqlAndReplaysExactTerminalResponse() throws Exception {
        ConfirmFixture fixture = createConfirmFixture("confirmation_reservation-block-same", 5_000_000L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE rule_execution_results IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> owner = executor.submit(() -> performConfirm(fixture, "block-same"));
            awaitBlockedQuery("rule_execution_results", 1);
            Future<MvcResult> replay = executor.submit(() -> performConfirm(fixture, "block-same"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("rule_execution_results"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("idempotency_records"));

            blocker.commit();
            MvcResult original = owner.get(10, TimeUnit.SECONDS);
            MvcResult repeated = replay.get(10, TimeUnit.SECONDS);
            assertThat(original.getResponse().getStatus()).isEqualTo(201);
            assertThat(repeated.getResponse().getStatus()).isEqualTo(201);
            assertThat(repeated.getResponse().getContentAsString())
                    .isEqualTo(original.getResponse().getContentAsString());
            assertCompleteBlockedLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelFirstBlocksACompetingConfirmAtIntentAndCommitsOneCoherentCancellation() throws Exception {
        ConfirmFixture fixture = createReviewFixture("manual_review-cancel-distinct");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE manual_review_cases IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> first = executor.submit(() -> performCancel(fixture, "cancel-distinct-a"));
            awaitBlockedQuery("manual_review_cases", 1);
            Future<MvcResult> second = executor.submit(() -> performConfirm(fixture, "confirm-during-cancel"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("manual_review_cases"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("payment_intents"));

            blocker.commit();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertCanceledReviewLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reviewConfirmFirstBlocksCancelAtIntentThenCancelClosesTheCommittedCase() throws Exception {
        ConfirmFixture fixture = createReviewReadyFixture("manual_review-confirm-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE manual_review_cases IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> confirm = executor.submit(() -> performConfirm(fixture, "confirm-first-review"));
            awaitBlockedQuery("manual_review_cases", 1);
            Future<MvcResult> cancel = executor.submit(() -> performCancel(fixture, "cancel-after-review"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("manual_review_cases"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("payment_intents"));

            blocker.commit();
            assertThat(confirm.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(cancel.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertCanceledReviewLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reviewCancelSameKeyWaitsAtCaseLockAndReplaysExactCanceledResponse() throws Exception {
        ConfirmFixture fixture = createReviewFixture("manual_review-cancel-same");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE manual_review_cases IN ACCESS EXCLUSIVE MODE");

            Future<MvcResult> owner = executor.submit(() -> performCancel(fixture, "cancel-same"));
            awaitBlockedQuery("manual_review_cases", 1);
            Future<MvcResult> replay = executor.submit(() -> performCancel(fixture, "cancel-same"));
            List<String> blockedQueries = awaitBlockedQueries(2);
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("manual_review_cases"));
            assertThat(blockedQueries).anyMatch(query -> query.toLowerCase().contains("idempotency_records"));

            blocker.commit();
            MvcResult original = owner.get(10, TimeUnit.SECONDS);
            MvcResult repeated = replay.get(10, TimeUnit.SECONDS);
            assertThat(original.getResponse().getStatus()).isEqualTo(200);
            assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
            assertThat(repeated.getResponse().getContentAsString())
                    .isEqualTo(original.getResponse().getContentAsString());
            assertCanceledReviewLineage();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reviewCursorPaginationExecutesAtPostgreSqlBoundary() throws Exception {
        ConfirmFixture fixture = createReviewFixture("manual_review-postgres-cursor-a");
        createReviewFixtureForMerchant(fixture, "manual_review-postgres-cursor-b");
        createReviewFixtureForMerchant(fixture, "manual_review-postgres-cursor-c");
        try {
            jdbcTemplate.execute("ALTER TABLE manual_review_cases DISABLE TRIGGER trg_manual_review_history");
            jdbcTemplate.update("UPDATE manual_review_cases SET created_at = ?",
                    OffsetDateTime.parse("2026-07-13T00:00:00Z"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE manual_review_cases ENABLE TRIGGER trg_manual_review_history");
        }

        MvcResult firstPage = mockMvc.perform(get("/fraud/reviews")
                        .header(API_KEY, fixture.apiKey()).param("status", "OPEN").param("limit", "2"))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstPageJson = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(firstPageJson).hasSize(2);
        UUID reviewId = UUID.fromString(firstPageJson.get(1).get("id").asText());

        MvcResult afterCursor = mockMvc.perform(get("/fraud/reviews")
                        .header(API_KEY, fixture.apiKey()).param("status", "OPEN").param("limit", "2")
                        .param("starting_after", reviewId.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode afterCursorJson = objectMapper.readTree(afterCursor.getResponse().getContentAsString());
        assertThat(afterCursorJson).hasSize(1);
        assertThat(afterCursorJson.get(0).get("id").asText())
                .isNotEqualTo(firstPageJson.get(0).get("id").asText())
                .isNotEqualTo(firstPageJson.get(1).get("id").asText());
    }

    @Test
    void manualReviewIdentityLifecycleAndTerminalHistoryAreAppendOnlyAtPostgreSqlBoundary() throws Exception {
        ConfirmFixture fixture = createReviewFixture("manual_review-postgres-history");
        UUID reviewId = jdbcTemplate.queryForObject("SELECT id FROM manual_review_cases", UUID.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE manual_review_cases SET reason_codes = '["ELEVATED_AMOUNT"]' WHERE id = ?
                """, reviewId)).hasMessageContaining("manual review history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM manual_review_cases WHERE id = ?", reviewId))
                .hasMessageContaining("manual review history is immutable");

        assertThat(performCancel(fixture, "manual_review-history-cancel").getResponse().getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE manual_review_cases
                SET status = 'OPEN', cancellation_reason = NULL, canceled_at = NULL
                WHERE id = ?
                """, reviewId)).hasMessageContaining("manual review history is immutable");
        assertCanceledReviewLineage();
    }

    private List<String> awaitBlockedQueries(int expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        List<String> queries = List.of();
        while (Instant.now().isBefore(deadline)) {
            queries = jdbcTemplate.queryForList("""
                    SELECT query
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                    """, String.class);
            if (queries.size() >= expectedCount) {
                return queries;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Expected " + expectedCount + " blocked PostgreSQL backends but saw " + queries);
    }

    private void awaitBlockedQuery(String fragment, int expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE ?
                    """, Integer.class, "%" + fragment + "%");
            if (count != null && count >= expectedCount) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Expected blocked PostgreSQL query containing " + fragment);
    }

    private void assertCompleteSingleLineage() {
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("feature_values")).isEqualTo(9);
        assertThat(countRows("rule_execution_results")).isEqualTo(3);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM feature_values fv
                JOIN fraud_decisions fd
                  ON fd.merchant_id = fv.merchant_id
                 AND fd.payment_attempt_id = fv.payment_attempt_id
                WHERE fv.observed_at <= fd.decision_started_at
                """, Integer.class)).isEqualTo(9);
    }

    private void assertCompleteBlockedLineage() {
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("feature_values")).isEqualTo(9);
        assertThat(countRows("rule_execution_results")).isEqualTo(3);
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents", String.class))
                .isEqualTo("BLOCKED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts", String.class))
                .isEqualTo("BLOCKED");
    }

    private void assertCanceledReviewLineage() {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents", String.class))
                .isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payment_attempts ORDER BY attempt_number DESC LIMIT 1
                """, String.class)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases", String.class))
                .isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT cancellation_reason FROM manual_review_cases", String.class))
                .isEqualTo("MERCHANT_CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions ORDER BY created_at DESC LIMIT 1",
                String.class)).isEqualTo("REVIEW");
        assertThat(countRows("authorization_voids")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    private ConfirmFixture createConfirmFixture(String suffix) throws Exception {
        return createConfirmFixture(suffix, 1200L);
    }

    private ConfirmFixture createConfirmFixture(String suffix, long amount) throws Exception {
        return createConfirmFixture(suffix, amount, "4999990000000001");
    }

    private ConfirmFixture createConfirmFixture(String suffix, long amount, String cardNumber) throws Exception {
        String merchantResponse = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"feature-lineage-%s"}
                                """.formatted(suffix)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode merchantJson = objectMapper.readTree(merchantResponse);
        String apiKey = merchantJson.get("apiKey").asText();
        return createConfirmFixtureForMerchant(
                UUID.fromString(merchantJson.get("id").asText()), apiKey, suffix, amount, cardNumber);
    }

    private ConfirmFixture createConfirmFixtureForMerchant(UUID merchantId, String apiKey, String suffix,
            long amount, String cardNumber) throws Exception {
        String customerResponse = mockMvc.perform(post("/customers")
                        .header(API_KEY, apiKey).contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s@example.com"}
                                """.formatted(suffix)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(objectMapper.readTree(customerResponse).get("id").asText());

        String methodResponse = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, "method-" + suffix)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"%s"}
                                """.formatted(customerId, cardNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID methodId = UUID.fromString(objectMapper.readTree(methodResponse).get("id").asText());

        String intentResponse = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, "intent-" + suffix)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":%d,"currency":"USD","captureMethod":"MANUAL"}
                                """.formatted(customerId, amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID intentId = UUID.fromString(objectMapper.readTree(intentResponse).get("id").asText());
        return new ConfirmFixture(merchantId, apiKey, intentId, methodId);
    }

    private ConfirmFixture createReviewFixture(String suffix) throws Exception {
        ConfirmFixture fixture = createReviewReadyFixture(suffix);
        MvcResult review = performConfirm(fixture, suffix + "-confirm-3");
        assertThat(review.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(review.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("IN_REVIEW");
        return fixture;
    }

    private ConfirmFixture createReviewFixtureForMerchant(ConfirmFixture merchantFixture, String suffix)
            throws Exception {
        ConfirmFixture fixture = createConfirmFixtureForMerchant(
                merchantFixture.merchantId(), merchantFixture.apiKey(), suffix, 99_999L, "4999990000000003");
        assertThat(performConfirm(fixture, suffix + "-confirm-1").getResponse().getStatus()).isEqualTo(201);
        assertThat(performConfirm(fixture, suffix + "-confirm-2").getResponse().getStatus()).isEqualTo(201);
        MvcResult review = performConfirm(fixture, suffix + "-confirm-3");
        assertThat(review.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(review.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("IN_REVIEW");
        return fixture;
    }

    private ConfirmFixture createReviewReadyFixture(String suffix) throws Exception {
        ConfirmFixture fixture = createConfirmFixture(suffix, 99_999L, "4999990000000003");
        assertThat(performConfirm(fixture, suffix + "-confirm-1").getResponse().getStatus()).isEqualTo(201);
        assertThat(performConfirm(fixture, suffix + "-confirm-2").getResponse().getStatus()).isEqualTo(201);
        return fixture;
    }

    private MvcResult performConfirm(ConfirmFixture fixture, String key) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/confirm", fixture.paymentIntentId())
                        .header(API_KEY, fixture.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"paymentMethodId":"%s"}
                                """.formatted(fixture.paymentMethodId())))
                .andReturn();
    }

    private MvcResult performCancel(ConfirmFixture fixture, String key) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/cancel", fixture.paymentIntentId())
                        .header(API_KEY, fixture.apiKey()).header(IDEMPOTENCY_KEY, key))
                .andReturn();
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record ConfirmFixture(UUID merchantId, String apiKey, UUID paymentIntentId, UUID paymentMethodId) {
    }
}
