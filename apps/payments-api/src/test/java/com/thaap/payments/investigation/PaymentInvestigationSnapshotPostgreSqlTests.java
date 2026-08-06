package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-TXN-001 against the engine that actually enforces it. H2 cannot certify a
 * PostgreSQL {@code REPEATABLE READ} claim, so every snapshot and lock assertion here runs on
 * a real PostgreSQL container with forced interleavings rather than hopeful timing.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class PaymentInvestigationSnapshotPostgreSqlTests {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("investigation-snapshot-owner");
        manager = scenario.operator(merchant, "snapshot-manager", "RISK_MANAGER");
    }

    @Test
    void theDetailReadDeclaresRepeatableReadAndReportsTheStreamVersionItActuallySaw()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "snapshot-declared");
        scenario.drainProjection();

        JsonNode snapshot = detail(reviewed).get("sourceSnapshot");
        assertThat(snapshot.get("consistency").asText()).isEqualTo("REPEATABLE_READ");
        assertThat(snapshot.get("authority").asText()).isEqualTo("SOURCE_DATABASE");
        long reported = snapshot.get("maximumPaymentStreamVersion").asLong();
        Long committed = jdbcTemplate.queryForObject("""
                SELECT MAX(stream_version) FROM domain_events
                WHERE merchant_id = ? AND stream_id = ?
                """, Long.class, merchant.id(), reviewed.paymentIntentId());
        assertThat(reported).isEqualTo(committed);
    }

    @Test
    void aWriterCommittingMidReadCannotSplitOneDetailAcrossTwoSnapshots() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "snapshot-interleaved");
        scenario.drainProjection();
        JsonNode before = detail(reviewed);

        // Force the writer strictly between two reads. If the reader took its own snapshot per
        // section, one response could show the pre-write lifecycle beside post-write review
        // rows: an analyst would be looking at a payment state that never existed.
        CountDownLatch writerCommitted = new CountDownLatch(1);
        ExecutorService writers = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = writers.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        jdbcTemplate.update("""
                                UPDATE payment_intents SET updated_at = ?
                                WHERE merchant_id = ? AND id = ?
                                """, Timestamp.from(clock.instant()), merchant.id(),
                                reviewed.paymentIntentId()));
                writerCommitted.countDown();
            });
            assertThat(writerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            writer.get(10, TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();
        }

        JsonNode after = detail(reviewed);
        // Each response is internally consistent: the lifecycle and its attempts agree, and the
        // reported stream version never regresses across reads.
        assertInternallyConsistent(before);
        assertInternallyConsistent(after);
        assertThat(after.at("/sourceSnapshot/maximumPaymentStreamVersion").asLong())
                .isGreaterThanOrEqualTo(
                        before.at("/sourceSnapshot/maximumPaymentStreamVersion").asLong());
    }

    @Test
    void aReadOnlyInvestigationTakesNoRowLockThatCouldBlockAPaymentMutation() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "snapshot-locks");
        scenario.drainProjection();

        CountDownLatch readerInFlight = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        ExecutorService readers = Executors.newSingleThreadExecutor();
        try {
            Future<?> reader = readers.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcTemplate.queryForList("""
                            SELECT id FROM payment_intents WHERE merchant_id = ? AND id = ?
                            """, merchant.id(), reviewed.paymentIntentId());
                    readerInFlight.countDown();
                    try {
                        releaseReader.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertThat(readerInFlight.await(10, TimeUnit.SECONDS)).isTrue();

            // While that read transaction is deliberately held open, a real payment mutation
            // must still complete. A workspace read that can stall authorization is worse than
            // no workspace at all.
            List<Map<String, Object>> blocking = jdbcTemplate.queryForList("""
                    SELECT blocked.pid FROM pg_locks blocked
                    JOIN pg_locks blocker
                      ON blocker.transactionid = blocked.transactionid AND blocker.granted
                    WHERE NOT blocked.granted
                    """);
            assertThat(blocking).as("an investigation read must not hold a blocking lock").isEmpty();

            long updated = jdbcTemplate.update("""
                    UPDATE payment_intents SET updated_at = ?
                    WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(clock.instant()), merchant.id(),
                    reviewed.paymentIntentId());
            assertThat(updated).isEqualTo(1);

            releaseReader.countDown();
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            releaseReader.countDown();
            readers.shutdownNow();
        }
    }

    @Test
    void theInvestigationReadLeavesNoOpenTransactionBehindOnPostgreSql() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "snapshot-cleanup");
        scenario.drainProjection();
        detail(reviewed);

        // A read-only snapshot that is never closed becomes an idle-in-transaction session,
        // which pins autovacuum and eventually degrades the whole database.
        Integer idleInTransaction = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE state = 'idle in transaction' AND datname = current_database()
                """, Integer.class);
        assertThat(idleInTransaction).isZero();
    }

    @Test
    void detailAndTimelineDeclareIndependentCutoffsRatherThanClaimingOneSnapshot()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "snapshot-cutoffs");
        scenario.drainProjection();

        JsonNode detail = detail(reviewed);
        // The projection-backed section must never inherit the source snapshot's authority.
        // Pretending they share a cutoff is how an analyst comes to believe a stale read model
        // is as trustworthy as the payment row itself.
        assertThat(detail.at("/sectionStatuses/paymentLifecycle/authority").asText())
                .isEqualTo("SOURCE_DATABASE");
        assertThat(detail.at("/sectionStatuses/analyticsContext/authority").asText())
                .isEqualTo("ACTIVE_PAYMENT_RISK_PROJECTION");
        assertThat(detail.at("/sourceSnapshot/sourceAsOf").asText()).isNotBlank();
        assertThat(detail.get("analyticsContext").isNull()).isFalse();
        assertThat(detail.at("/analyticsContext/freshness").isObject()).isTrue();
    }

    private void assertInternallyConsistent(JsonNode detail) {
        JsonNode attempts = detail.at("/paymentLifecycle/attempts");
        assertThat(attempts).isNotEmpty();
        String latestAttemptId = detail.at("/identity/latestPaymentAttemptId").asText();
        assertThat(attempts.get(attempts.size() - 1).get("id").asText())
                .as("identity must name an attempt from the same snapshot as the lifecycle")
                .isEqualTo(latestAttemptId);
    }

    private JsonNode detail(Payment payment) throws Exception {
        return scenario.json(get("/risk/workspace/payments/{id}", payment.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
    }
}
