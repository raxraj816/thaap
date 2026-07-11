package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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
class Chapter22FeatureLineagePostgreSqlConcurrencyTests {

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
        clearInvocations(featureMaterializer, processorAdapter);
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
                any(PaymentIntent.class), any(PaymentAttempt.class), any(PaymentMethod.class), any(Instant.class));

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
        assertThat(countRows("feature_values")).isEqualTo(7);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM feature_values fv
                JOIN fraud_decisions fd
                  ON fd.merchant_id = fv.merchant_id
                 AND fd.payment_attempt_id = fv.payment_attempt_id
                WHERE fv.observed_at <= fd.decision_started_at
                """, Integer.class)).isEqualTo(7);
    }

    private ConfirmFixture createConfirmFixture(String suffix) throws Exception {
        String merchantResponse = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"chapter-22-%s"}
                                """.formatted(suffix)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode merchantJson = objectMapper.readTree(merchantResponse);
        String apiKey = merchantJson.get("apiKey").asText();

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
                                {"customerId":"%s","cardNumber":"4999990000000001"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID methodId = UUID.fromString(objectMapper.readTree(methodResponse).get("id").asText());

        String intentResponse = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, apiKey).header(IDEMPOTENCY_KEY, "intent-" + suffix)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":1200,"currency":"USD","captureMethod":"MANUAL"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID intentId = UUID.fromString(objectMapper.readTree(intentResponse).get("id").asText());
        return new ConfirmFixture(UUID.fromString(merchantJson.get("id").asText()), apiKey, intentId, methodId);
    }

    private MvcResult performConfirm(ConfirmFixture fixture, String key) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/confirm", fixture.paymentIntentId())
                        .header(API_KEY, fixture.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"paymentMethodId":"%s"}
                                """.formatted(fixture.paymentMethodId())))
                .andReturn();
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record ConfirmFixture(UUID merchantId, String apiKey, UUID paymentIntentId, UUID paymentMethodId) {
    }
}
