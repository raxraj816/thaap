package com.thaap.payments.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DomainEventPostgreSqlTests {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private DomainEventWriter eventWriter;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private EventStreamHeadRepository streamHeadRepository;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    @Test
    void freshMigrationInstallsClosedConstraintsAndImmutableHistoryGuards()
            throws Exception {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('65', '66') AND success = TRUE
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                WHERE tgname IN (
                    'trg_domain_events_immutable',
                    'trg_event_stream_heads_no_regression',
                    'trg_event_publications_identity_immutable',
                    'trg_platform_operator_requests_immutable',
                    'trg_platform_operators_lifecycle_protected',
                    'trg_platform_operator_credentials_lifecycle_protected',
                    'trg_platform_operator_bootstrap_state_protected')
                  AND NOT tgisinternal
                """, Integer.class)).isEqualTo(7);

        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("postgres-event-guard", "postgres-event-guard-key"));
        DomainEventWriteResult result = new TransactionTemplate(transactionManager)
                .execute(status -> eventWriter.append(command(merchant.getId(),
                        UUID.randomUUID(), "guard-event")));
        assertThat(result).isNotNull();
        DomainEvent event = eventRepository.findById(result.eventId()).orElseThrow();

        assertThat(sha256(event.getPayloadBytes())).isEqualTo(event.getPayloadSha256());
        assertThat(sha256(event.getEventRecordBytes())).isEqualTo(event.getEventRecordSha256());
        assertThat(new String(event.getEventRecordBytes(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"payment_intent.created\"")
                .contains("\"payloadJson\"");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE domain_events SET correlation_id = 'rewritten' WHERE id = ?",
                event.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("domain event history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM event_publications WHERE domain_event_id = ?", event.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("event publication evidence cannot be deleted");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE event_stream_heads SET current_version = 0
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, merchant.getId(), event.getStreamId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cannot regress");
    }

    @Test
    void retainedV64UpgradePreservesSourceAndAuditTruthWithoutFabricatingEvents() {
        String schema = "event_retained_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
            DriverManagerDataSource retainedDataSource = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway baseline = Flyway.configure()
                    .dataSource(retainedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("64")
                    .load();
            baseline.migrate();
            JdbcTemplate retained = new JdbcTemplate(retainedDataSource);
            UUID merchantId = UUID.randomUUID();
            UUID auditId = UUID.randomUUID();
            retained.update("""
                    INSERT INTO %s.merchants (
                        id, name, status, api_key_prefix, api_key_hash, created_at)
                    VALUES (?, 'retained-event-merchant', 'ACTIVE', 'retained_', ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "a".repeat(64));
            retained.update("""
                    INSERT INTO %s.audit_logs (
                        id, merchant_id, actor_type, actor_id, action, aggregate_type,
                        aggregate_id, request_id, created_at)
                    VALUES (?, ?, 'MERCHANT_API_KEY', 'retained_', 'retained.action',
                        'merchant', ?, 'retained-request', CURRENT_TIMESTAMP)
                    """.formatted(schema), auditId, merchantId, merchantId);

            Flyway.configure()
                    .dataSource(retainedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".merchants WHERE id = ?", Integer.class,
                    merchantId)).isEqualTo(1);
            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".audit_logs WHERE id = ?", Integer.class,
                    auditId)).isEqualTo(1);
            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".domain_events", Integer.class)).isZero();
            assertThat(retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".event_publications", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void firstWriterOwnsStreamLockAndSecondReceivesNextVersionAfterCommit()
            throws Exception {
        forceWriterOrder("first-writer", "second-writer");
    }

    @Test
    void reversedArrivalOwnsVersionOneAndOriginalCallerReceivesVersionTwo()
            throws Exception {
        forceWriterOrder("second-arrival-first", "original-arrival-second");
    }

    private void forceWriterOrder(String firstCorrelation, String secondCorrelation)
            throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("forced-event-order-" + firstCorrelation,
                        "forced-event-order-key-" + UUID.randomUUID()));
        UUID streamId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO event_stream_heads (
                    merchant_id, stream_type, stream_id, current_version, updated_at)
                VALUES (?, 'PAYMENT', ?, 0, CURRENT_TIMESTAMP)
                """, merchant.getId(), streamId);

        CountDownLatch firstOwnsLock = new CountDownLatch(1);
        CountDownLatch secondAttemptedLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger secondBackendPid =
                new java.util.concurrent.atomic.AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DomainEventWriteResult> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        streamHeadRepository.findForUpdate(
                                        merchant.getId(), DomainStreamType.PAYMENT, streamId)
                                .orElseThrow();
                        firstOwnsLock.countDown();
                        await(releaseFirst);
                        return eventWriter.append(command(merchant.getId(), streamId,
                                firstCorrelation));
                    }));
            assertThat(firstOwnsLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DomainEventWriteResult> second = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        secondBackendPid.set(jdbcTemplate.queryForObject(
                                "SELECT pg_backend_pid()", Integer.class));
                        secondAttemptedLock.countDown();
                        return eventWriter.append(command(merchant.getId(), streamId,
                                secondCorrelation));
                    }));
            assertThat(secondAttemptedLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitStreamHeadLockWait(secondBackendPid.get());
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            DomainEventWriteResult firstResult = first.get(5, TimeUnit.SECONDS);
            DomainEventWriteResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.streamVersion()).as(firstCorrelation).isEqualTo(1L);
            assertThat(secondResult.streamVersion()).as(secondCorrelation).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForList("""
                    SELECT CAST(stream_version AS VARCHAR) || ':' || correlation_id
                      FROM domain_events
                     WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                     ORDER BY stream_version
                    """, String.class, merchant.getId(), streamId))
                    .containsExactly("1:" + firstCorrelation, "2:" + secondCorrelation);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM event_publications p
                    JOIN domain_events e ON e.merchant_id = p.merchant_id
                                         AND e.id = p.domain_event_id
                    WHERE e.merchant_id = ? AND e.stream_type = 'PAYMENT'
                      AND e.stream_id = ?
                    """, Integer.class, merchant.getId(), streamId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT current_version FROM event_stream_heads
                    WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                    """, Long.class, merchant.getId(), streamId)).isEqualTo(2L);
        } finally {
            releaseFirst.countDown();
        }
    }

    private void awaitStreamHeadLockWait(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE pid = ? AND wait_event_type = 'Lock'
                    """, Integer.class, backendPid);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Second event writer did not enter a PostgreSQL lock wait");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for forced event ordering");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during forced event ordering", exception);
        }
    }

    private DomainEventCommand command(UUID merchantId, UUID streamId,
            String correlationId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 5000);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, streamId,
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, DatabaseInstant.now().minusSeconds(1),
                correlationId, null, correlationId + "-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM,
                        "postgres-event-proof"), TrafficOrigin.MERCHANT_API, payload);
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
