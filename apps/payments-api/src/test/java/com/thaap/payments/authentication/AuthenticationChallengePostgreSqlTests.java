package com.thaap.payments.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationChallengePostgreSqlTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AuthenticationChallengeService challengeService;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void forcedCompletionFirstMakesCancellationWaitAndPreservesCapturedTruth() throws Exception {
        Fixture fixture = createFixture("complete-first", true);
        CountDownLatch completionOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> completion = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    challengeService.complete(fixture.context(), fixture.attemptId(), fixture.challengeId(),
                            AuthenticationChallengeService.CompletionOutcome.SUCCESS,
                            fixture.clientSecret(), "complete-first");
                    completionOwnsPaymentLock.countDown();
                    await(releaseCompletion);
                });
            });
            assertThat(completionOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.intentId());

            Future<MvcResult> cancellation = executor.submit(() -> mockMvc.perform(
                            post("/payment_intents/{id}/cancel", fixture.intentId())
                                    .header(API_KEY, fixture.apiKey())
                                    .header(IDEMPOTENCY_KEY, "complete-first-cancel"))
                    .andReturn());
            awaitDatabaseLockWait("payment_intents");
            releaseCompletion.countDown();

            completion.get(5, TimeUnit.SECONDS);
            assertThat(cancellation.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }

        assertSuccessTruth(fixture);
    }

    @Test
    void forcedCancellationFirstMakesCompletionWaitAndPreservesCanceledTruth() throws Exception {
        Fixture fixture = createFixture("cancel-first", true);
        CountDownLatch cancellationOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    try {
                        mockMvc.perform(post("/payment_intents/{id}/cancel", fixture.intentId())
                                        .header(API_KEY, fixture.apiKey())
                                        .header(IDEMPOTENCY_KEY, "cancel-first-cancel"))
                                .andExpect(status().isOk());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    cancellationOwnsPaymentLock.countDown();
                    await(releaseCancellation);
                });
            });
            assertThat(cancellationOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.intentId());

            Future<AuthenticationChallengeService.CompletionResult> completion = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus ->
                            challengeService.complete(fixture.context(), fixture.attemptId(), fixture.challengeId(),
                                    AuthenticationChallengeService.CompletionOutcome.SUCCESS,
                                    fixture.clientSecret(), "cancel-first-complete")));
            awaitDatabaseLockWait("payment_intents");
            releaseCancellation.countDown();

            cancellation.get(5, TimeUnit.SECONDS);
            AuthenticationChallengeService.CompletionResult result = completion.get(5, TimeUnit.SECONDS);
            assertThat(result.challenge().getStatus()).isEqualTo(AuthenticationChallengeStatus.CANCELED);
        }

        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.intentId()))
                .isEqualTo("CANCELED");
        assertThat(value("SELECT status FROM payment_attempts WHERE id = ?", fixture.attemptId()))
                .isEqualTo("CANCELED");
        assertThat(value("SELECT status FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .isEqualTo("CANCELED");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
    }

    @Test
    void forcedCompetingCompletionsSerializeToOneProcessorAndCaptureTruth() throws Exception {
        Fixture fixture = createFixture("competing", true);
        CountDownLatch firstOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AuthenticationChallengeService.CompletionResult> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus -> {
                        AuthenticationChallengeService.CompletionResult result = challengeService.complete(
                                fixture.context(), fixture.attemptId(), fixture.challengeId(),
                                AuthenticationChallengeService.CompletionOutcome.SUCCESS,
                                fixture.clientSecret(), "competing-first");
                        firstOwnsPaymentLock.countDown();
                        await(releaseFirst);
                        return result;
                    }));
            assertThat(firstOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AuthenticationChallengeService.CompletionResult> second = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus ->
                            challengeService.complete(fixture.context(), fixture.attemptId(), fixture.challengeId(),
                                    AuthenticationChallengeService.CompletionOutcome.SUCCESS,
                                    fixture.clientSecret(), "competing-second")));
            awaitDatabaseLockWait("payment_intents");
            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).challenge().getStatus())
                    .isEqualTo(AuthenticationChallengeStatus.SUCCEEDED);
            assertThat(second.get(5, TimeUnit.SECONDS).challenge().getStatus())
                    .isEqualTo(AuthenticationChallengeStatus.SUCCEEDED);
        }

        assertSuccessTruth(fixture);
    }

    @Test
    void forcedExpiryFirstMakesCompletionObserveExpiredTruthWithoutProcessor() throws Exception {
        Fixture fixture = createFixture("expiry-first", true);
        CountDownLatch expiryOwnsChallengeLock = new CountDownLatch(1);
        CountDownLatch releaseExpiry = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> expiry = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    // Test-only clock-boundary setup: production never mutates expiry. SET LOCAL
                    // is transaction-scoped and re-enables every trigger automatically at commit.
                    jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
                    jdbcTemplate.update("UPDATE authentication_challenges SET expires_at = created_at + "
                            + "INTERVAL '1 microsecond', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            fixture.challengeId());
                    expiryOwnsChallengeLock.countDown();
                    await(releaseExpiry);
                });
            });
            assertThat(expiryOwnsChallengeLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("authentication_challenges", fixture.challengeId());

            Future<AuthenticationChallengeService.CompletionResult> completion = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus ->
                            challengeService.complete(fixture.context(), fixture.attemptId(), fixture.challengeId(),
                                    AuthenticationChallengeService.CompletionOutcome.SUCCESS,
                                    fixture.clientSecret(), "expiry-first-complete")));
            awaitDatabaseLockWait("authentication_challenges");
            releaseExpiry.countDown();
            expiry.get(5, TimeUnit.SECONDS);
            assertThat(completion.get(5, TimeUnit.SECONDS).challenge().getStatus())
                    .isEqualTo(AuthenticationChallengeStatus.EXPIRED);
        }

        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.intentId()))
                .isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    void postgresRejectsSourceSubstitutionAndTerminalHistoryMutation() throws Exception {
        Fixture fixture = createFixture("history-guards", true);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE authentication_challenges
                   SET source = 'PROCESSOR'
                 WHERE id = ?
                """, fixture.challengeId()))
                .hasMessageContaining("authentication challenge history is immutable");

        complete(fixture, "history-guards-complete", 200);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE authentication_challenges
                   SET result_code = 'AUTHENTICATION_FAILED'
                 WHERE id = ?
                """, fixture.challengeId()))
                .hasMessageContaining("authentication challenge history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .hasMessageContaining("authentication challenge history is immutable");
        assertSuccessTruth(fixture);
    }

    @ParameterizedTest(name = "authentication rollback at {0}")
    @ValueSource(strings = {"challenge", "authorization", "ledger", "audit", "webhook", "idempotency"})
    void forcedLocalFailureRollsBackEveryTruthAndSameKeyRetries(String failurePosition) throws Exception {
        Fixture fixture = createFixture("rollback-" + failurePosition, true);
        int auditBefore = count("audit_logs");
        int webhookBefore = count("webhook_events");
        ConstraintSpec constraint = constraintFor(failurePosition);
        String key = "authentication-rollback-" + failurePosition;

        jdbcTemplate.execute("ALTER TABLE " + constraint.table()
                + " ADD CONSTRAINT ck_test_authentication_challenge_forced_failure CHECK ("
                + constraint.expression() + ") NOT VALID");
        try {
            complete(fixture, key, 500);
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + constraint.table()
                    + " DROP CONSTRAINT IF EXISTS ck_test_authentication_challenge_forced_failure");
        }

        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.intentId()))
                .isEqualTo("REQUIRES_ACTION");
        assertThat(value("SELECT status FROM payment_attempts WHERE id = ?", fixture.attemptId()))
                .isEqualTo("REQUIRES_ACTION");
        assertThat(value("SELECT status FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .isEqualTo("PENDING");
        assertThat(count("authorizations")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("audit_logs")).isEqualTo(auditBefore);
        assertThat(count("webhook_events")).isEqualTo(webhookBefore);

        complete(fixture, key, 200);
        assertSuccessTruth(fixture);
    }

    private Fixture createFixture(String name, boolean webhook) throws Exception {
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"authentication_challenge-pg-" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID merchantId = UUID.fromString(merchant.get("id").asText());
        String apiKey = merchant.get("apiKey").asText();
        if (webhook) {
            mockMvc.perform(post("/webhook_endpoints")
                            .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-webhook")
                            .contentType(APPLICATION_JSON).content("""
                                    {"url":"http://127.0.0.1:9191/authentication-challenge",
                                     "enabledEvents":["payment_intent.captured",
                                       "authentication_challenge.created",
                                       "authentication_challenge.completed"]}
                                    """))
                    .andExpect(status().isCreated());
        }
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, apiKey).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + name + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4999990000000001"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":100000,"currency":"USD",
                                 "captureMethod":"AUTOMATIC"}
                                """.formatted(customer.get("id").asText())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode attempt = objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm",
                        intent.get("id").asText())
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, name + "-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + method.get("id").asText() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode action = attempt.get("nextAction");
        return new Fixture(new MerchantContext(merchantId, "authentication_challenge-pg-" + name, "test"), apiKey,
                UUID.fromString(intent.get("id").asText()), UUID.fromString(attempt.get("id").asText()),
                UUID.fromString(action.get("challengeId").asText()), action.get("clientSecret").asText());
    }

    private void complete(Fixture fixture, String key, int expectedStatus) throws Exception {
        mockMvc.perform(post("/payment_attempts/{attemptId}/authentication_challenges/{challengeId}/complete",
                        fixture.attemptId(), fixture.challengeId())
                        .header(API_KEY, fixture.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCESS\",\"clientSecret\":\""
                                + fixture.clientSecret() + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private void assertSuccessTruth(Fixture fixture) {
        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.intentId()))
                .isEqualTo("CAPTURED");
        assertThat(value("SELECT status FROM payment_attempts WHERE id = ?", fixture.attemptId()))
                .isEqualTo("AUTHORIZED");
        assertThat(value("SELECT status FROM authentication_challenges WHERE id = ?", fixture.challengeId()))
                .isEqualTo("SUCCEEDED");
        assertThat(count("authentication_challenges")).isOne();
        assertThat(count("authorizations")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(2);
    }

    private ConstraintSpec constraintFor(String position) {
        return switch (position) {
            case "challenge" -> new ConstraintSpec("authentication_challenges", "status <> 'SUCCEEDED'");
            case "authorization" -> new ConstraintSpec("authorizations", "status <> 'APPROVED'");
            case "ledger" -> new ConstraintSpec("ledger_entries", "FALSE");
            case "audit" -> new ConstraintSpec("audit_logs",
                    "action NOT IN ('payment_intent.captured', 'authentication_challenge.completed')");
            case "webhook" -> new ConstraintSpec("webhook_events",
                    "event_type NOT IN ('payment_intent.captured', 'authentication_challenge.completed')");
            case "idempotency" -> new ConstraintSpec("idempotency_records", "status <> 'COMPLETED'");
            default -> throw new IllegalArgumentException("Unknown failure position " + position);
        };
    }

    private void awaitRowLockConflict(String table, UUID id) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                connection.setAutoCommit(false);
                statement.setObject(1, id);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected row lock was not observed for " + table);
    }

    private void awaitDatabaseLockWait(String table) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND wait_event_type = 'Lock'
                          AND query LIKE ?)
                    """, Boolean.class, "%" + table + "%");
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing operation did not wait on " + table);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for Authentication challenge forced ordering latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Authentication challenge forced ordering was interrupted", exception);
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String value(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private record Fixture(MerchantContext context, String apiKey, UUID intentId, UUID attemptId,
            UUID challengeId, String clientSecret) {
    }

    private record ConstraintSpec(String table, String expression) {
    }
}
