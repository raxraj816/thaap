package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C46-CON-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p>The row's invariant is that risk-operations event writing joins the existing transaction and
 * lock order rather than inventing one, so concurrent operational transitions produce one legal
 * source state and a gap-free event sequence. Two properties carry that, and both are asserted
 * here on real PostgreSQL because both are about row locks: the stream allocates versions under
 * contention without gaps or duplicates, and a losing or rolled-back transition leaves no event,
 * publication or stream-head evidence behind.
 *
 * <p>The per-domain orderings the row also names — dual control, review resolution, dispute
 * submit/expire, webhook disable/redelivery, scenario lease — are held by each domain's own
 * concurrency suite. What was missing, and what this adds, is that the event boundary underneath
 * them is itself safe under contention.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.event-publication.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class RiskOperationsEventPostgreSqlConcurrencyTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private RiskOperationsEventProducer producer;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    @Test
    void concurrentOperationalTransitionsProduceAGapFreeStreamInEitherOrder() throws Exception {
        assertGapFreeUnderContention("resolve-then-label", true);
        assertGapFreeUnderContention("label-then-resolve", false);
    }

    /**
     * Two operational transitions on one payment stream, released together. Whichever commits
     * first must take version one and the other version two, with no gap, no duplicate and one
     * publication each. A stream that allocated versions outside the lock would produce either a
     * collision or a hole, and both are invisible until something replays the stream.
     */
    private void assertGapFreeUnderContention(String label, boolean resolveFirst)
            throws Exception {
        Fixture fixture = seedPaymentStream(label);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable resolve = () -> producer.manualReviewResolved(fixture.context(),
                fixture.paymentIntentId(), DatabaseInstant.now().minusSeconds(1),
                label + "-resolve", "CONFIRMED_FRAUD", "ACCOUNT_TAKEOVER",
                TrafficOrigin.MERCHANT_API);
        Runnable recordLabel = () -> producer.fraudLabelRecorded(fixture.context(),
                fixture.paymentIntentId(), DatabaseInstant.now().minusSeconds(1),
                label + "-label", "FRAUD", "MANUAL_REVIEW", DatabaseInstant.now().minusSeconds(1),
                "ACCOUNT_TAKEOVER", TrafficOrigin.MERCHANT_API);
        // Submission order is swapped between the two calls so both arrival orders are
        // exercised deliberately rather than left to the scheduler.
        Runnable leading = resolveFirst ? resolve : recordLabel;
        Runnable trailing = resolveFirst ? recordLabel : resolve;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> appendWhenReleased(ready, start, leading));
            Future<?> second = executor.submit(() -> appendWhenReleased(ready, start, trailing));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(streamVersions(fixture.paymentIntentId()))
                .describedAs("%s produced a gapped or duplicated stream", label)
                .containsExactly(1L, 2L);
        assertThat(eventCount(fixture.paymentIntentId())).isEqualTo(2);
        assertThat(publicationCount(fixture.paymentIntentId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_version FROM event_stream_heads
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, Long.class, fixture.merchantId(), fixture.paymentIntentId()))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT event_type FROM domain_events WHERE stream_id = ?
                """, String.class, fixture.paymentIntentId()))
                .containsExactlyInAnyOrder("manual_review.resolved", "fraud_label.recorded");
    }

    /**
     * A transition that rolls back must leave nothing. The event, its publication row and the
     * stream-head advance are all inside the caller's transaction, so a losing or failed
     * operational transition cannot leave evidence that it happened.
     */
    @Test
    void aRolledBackTransitionLeavesNoEventPublicationOrStreamAdvance() {
        Fixture fixture = seedPaymentStream("rollback");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            producer.manualReviewResolved(fixture.context(), fixture.paymentIntentId(),
                    DatabaseInstant.now().minusSeconds(1), "rollback-request", "CONFIRMED_FRAUD",
                    "ACCOUNT_TAKEOVER", TrafficOrigin.MERCHANT_API);
            throw new IllegalStateException("operational transition failed after the event");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(eventCount(fixture.paymentIntentId())).isZero();
        assertThat(publicationCount(fixture.paymentIntentId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM event_stream_heads
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, Integer.class, fixture.merchantId(), fixture.paymentIntentId())).isZero();

        // The stream is still usable afterwards and starts at version one, so the rollback
        // released its allocation rather than burning it.
        transaction.executeWithoutResult(status -> producer.manualReviewResolved(
                fixture.context(), fixture.paymentIntentId(), DatabaseInstant.now().minusSeconds(1),
                "rollback-retry-request", "CONFIRMED_FRAUD", "ACCOUNT_TAKEOVER",
                TrafficOrigin.MERCHANT_API));
        assertThat(streamVersions(fixture.paymentIntentId())).containsExactly(1L);
    }

    private void appendWhenReleased(CountDownLatch ready, CountDownLatch start,
            Runnable operation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("contention never started");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        transaction.executeWithoutResult(status -> operation.run());
    }

    private List<Long> streamVersions(UUID streamId) {
        return jdbcTemplate.queryForList("""
                SELECT stream_version FROM domain_events WHERE stream_id = ?
                ORDER BY stream_version
                """, Long.class, streamId);
    }

    private int eventCount(UUID streamId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_events WHERE stream_id = ?", Integer.class, streamId);
    }

    private int publicationCount(UUID streamId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM event_publications publication
                JOIN domain_events event ON event.id = publication.domain_event_id
                WHERE event.stream_id = ?
                """, Integer.class, streamId);
    }

    private Fixture seedPaymentStream(String label) {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, CURRENT_TIMESTAMP)
                """, merchantId, label + "-merchant", "thaap_test_a", uniqueHash());
        jdbcTemplate.update("""
                INSERT INTO customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, customerId, merchantId, uniqueHash());
        jdbcTemplate.update("""
                INSERT INTO payment_intents (id, merchant_id, customer_id, amount, currency,
                    capture_method, status, metadata, created_at, updated_at)
                VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', 'SUCCEEDED', '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, paymentIntentId, merchantId, customerId);
        return new Fixture(new RiskOperatorContext(merchantId, UUID.randomUUID(),
                "rop_" + label, RiskOperatorRole.RISK_MANAGER, "rop_live_a"),
                merchantId, paymentIntentId);
    }

    /** Merchant API key hashes are globally unique, so fixtures cannot share one. */
    private String uniqueHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private record Fixture(RiskOperatorContext context, UUID merchantId, UUID paymentIntentId) { }
}
