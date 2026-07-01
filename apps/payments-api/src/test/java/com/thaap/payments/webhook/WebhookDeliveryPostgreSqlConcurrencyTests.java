package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WebhookDeliveryPostgreSqlConcurrencyTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private WebhookEndpointService endpointService;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Autowired
    private WebhookDeliveryClaimService claimService;

    @Autowired
    private WebhookDeliveryFinalizationService finalizationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void concurrentWorkersCreateOneClaimAndOneAttemptNumberWinner() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("claim-race", "thaap_claim_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "claim-race", merchant.getApiKeyPrefix());
        endpointService.create(context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "claim-race-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant now = Instant.now();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<WebhookDeliveryClaim>> first = executor.submit(() -> claim(ready, start, now));
            Future<List<WebhookDeliveryClaim>> second = executor.submit(() -> claim(ready, start, now));
            ready.await();
            start.countDown();

            List<WebhookDeliveryClaim> firstClaims = first.get();
            List<WebhookDeliveryClaim> secondClaims = second.get();
            assertThat(firstClaims.size() + secondClaims.size()).isEqualTo(1);
        }

        WebhookDelivery claimed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        assertThat(claimed.getClaimToken()).isNotNull();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1))
                .get()
                .extracting(WebhookDeliveryAttempt::getOutcome)
                .isEqualTo(WebhookAttemptOutcome.IN_PROGRESS);
        assertThat(attemptRepository.count()).isEqualTo(1);
    }

    @Test
    void forcedRotationBeforeDisableEndsDisabled() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("rotate-before-disable", "thaap_rotate_before_disable"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "rotate-before-disable", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "endpoint-state-create");
        CountDownLatch rotationOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> rotation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointService.rotateSecret(
                            context, created.endpoint().getId(), "endpoint-state-rotate");
                    rotationOwnsEndpointLock.countDown();
                    await(releaseRotation);
                });
                return null;
            });
            rotationOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(
                            context, created.endpoint().getId(), "endpoint-state-disable");
                });
                return null;
            });
            disableStarted.await();
            awaitBackendLockWait(disableBackendPid.get());

            releaseRotation.countDown();
            rotation.get();
            disable.get();
        } finally {
            releaseRotation.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
    }

    @Test
    void forcedDisableBeforeRotationRejectsReactivation() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-before-rotate", "thaap_disable_before_rotate"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-before-rotate", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "endpoint-state-create");
        CountDownLatch disableOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch rotationStarted = new CountDownLatch(1);
        AtomicInteger rotationBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointService.disable(
                            context, created.endpoint().getId(), "endpoint-state-disable");
                    disableOwnsEndpointLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            disableOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> rotation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    rotationBackendPid.set(currentBackendPid());
                    rotationStarted.countDown();
                    endpointService.rotateSecret(
                            context, created.endpoint().getId(), "endpoint-state-rotate");
                });
                return null;
            });
            rotationStarted.await();
            awaitBackendLockWait(rotationBackendPid.get());

            releaseDisable.countDown();
            disable.get();
            assertThatThrownBy(rotation::get)
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .rootCause()
                    .satisfies(exception -> assertThat(
                            ((org.springframework.web.server.ResponseStatusException) exception)
                                    .getStatusCode().value())
                            .isEqualTo(409));
        } finally {
            releaseDisable.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
    }

    @Test
    void forcedRoutingBeforeDisableExhaustsTheCommittedDelivery() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("route-before-disable", "thaap_route_before_disable"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "route-before-disable", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "delivery-disable-create");
        CountDownLatch routingOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseRouting = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deliveryCreation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    deliveryService.createDeliveriesInCurrentTransaction(
                            merchant.getId(), "refund.created", UUID.randomUUID());
                    routingOwnsEndpointLock.countDown();
                    await(releaseRouting);
                });
                return null;
            });
            routingOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(
                            context, created.endpoint().getId(), "delivery-disable-action");
                });
                return null;
            });
            disableStarted.await();
            awaitBackendLockWait(disableBackendPid.get());

            releaseRouting.countDown();
            deliveryCreation.get();
            disable.get();
        } finally {
            releaseRouting.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(deliveryRepository.findAll()).singleElement()
                .satisfies(delivery -> assertThat(delivery.getStatus())
                        .isEqualTo(WebhookDeliveryStatus.EXHAUSTED));
        assertThat(claimService.claimDue(Instant.now().plusSeconds(60), 10)).isEmpty();
    }

    @Test
    void forcedDisableBeforeRoutingCreatesNoEventOrDelivery() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-before-route", "thaap_disable_before_route"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-before-route", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "delivery-disable-create");
        CountDownLatch disableOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch routingStarted = new CountDownLatch(1);
        AtomicInteger routingBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointService.disable(
                            context, created.endpoint().getId(), "delivery-disable-action");
                    disableOwnsEndpointLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            disableOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> deliveryCreation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    routingBackendPid.set(currentBackendPid());
                    routingStarted.countDown();
                        deliveryService.createDeliveriesInCurrentTransaction(
                                merchant.getId(), "refund.created", UUID.randomUUID());
                });
                return null;
            });
            routingStarted.await();
            awaitBackendLockWait(routingBackendPid.get());

            releaseDisable.countDown();
            disable.get();
            deliveryCreation.get();
        } finally {
            releaseDisable.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(deliveryRepository.count()).isZero();
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void concurrentRetryFinalizationAndDisableCannotLeaveRetryScheduled() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("finalize-disable-race", "thaap_finalize_disable_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "finalize-disable-race", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "finalize-disable-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = Instant.now();
        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt)).isTrue();
        CountDownLatch deliveryLocked = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    deliveryRepository.findByIdForUpdate(delivery.getId()).orElseThrow();
                    deliveryLocked.countDown();
                    await(releaseDelivery);
                });
                return null;
            });
            deliveryLocked.await();

            Future<WebhookDeliveryFinalizationResult> finalization = executor.submit(() ->
                    finalizationService.failRetryably(
                            claim, claimedAt.plusSeconds(1), true, 503,
                            WebhookDeliveryErrorCode.HTTP_NON_SUCCESS));
            awaitEndpointLockConflict(endpoint.endpoint().getId());
            Future<?> disable = executor.submit(() -> {
                endpointService.disable(
                        context, endpoint.endpoint().getId(), "finalize-disable-action");
                return null;
            });

            releaseDelivery.countDown();
            lockHolder.get();
            assertThat(finalization.get().finalized()).isTrue();
            disable.get();
        } finally {
            releaseDelivery.countDown();
        }

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(claimService.claimDue(claimedAt.plusSeconds(600), 1)).isEmpty();
    }

    @Test
    void disabledEndpointStillRecoversExpiredInFlightAttemptOnPostgreSql() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-crash-pg", "thaap_disable_crash_pg"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-crash-pg", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-crash-pg-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt.plusSeconds(1))).isTrue();
        endpointService.disable(context, endpoint.endpoint().getId(), "disable-crash-pg-action");

        assertThat(claimService.claimDue(claimedAt.plusSeconds(32), 1)).isEmpty();

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(attempt.getHttpRequestSent()).isNull();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 2))
                .isEmpty();
    }

    @Test
    void concurrentClaimDoesNotInflateBulkDisableMetrics() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-metric-race", "thaap_disable_metric_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-metric-race", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-metric-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        double terminalBefore = meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count();
        double disabledBefore = meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "ENDPOINT_DISABLED").count();
        CountDownLatch claimOwnsDeliveryLock = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> claim = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    int updated = jdbcTemplate.update("""
                            UPDATE webhook_deliveries
                            SET status = 'DELIVERING',
                                attempt_count = 1,
                                last_attempt_at = ?,
                                claim_token = ?,
                                lease_expires_at = ?
                            WHERE id = ?
                            """,
                            java.sql.Timestamp.from(Instant.now()),
                            UUID.randomUUID(),
                            java.sql.Timestamp.from(Instant.now().plusSeconds(30)),
                            delivery.getId());
                    assertThat(updated).isEqualTo(1);
                    claimOwnsDeliveryLock.countDown();
                    await(releaseClaim);
                });
                return null;
            });
            claimOwnsDeliveryLock.await();

            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(
                            context, endpoint.endpoint().getId(), "disable-metric-action");
                });
                return null;
            });
            disableStarted.await();
            awaitBackendLockWait(disableBackendPid.get());

            releaseClaim.countDown();
            claim.get();
            disable.get();
        } finally {
            releaseClaim.countDown();
        }

        assertThat(endpointRepository.findById(endpoint.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count() - terminalBefore)
                .isZero();
        assertThat(meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "ENDPOINT_DISABLED").count() - disabledBefore)
                .isZero();
    }

    private void awaitEndpointLockConflict(UUID endpointId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT id
                            FROM webhook_endpoints
                            WHERE id = ?
                            FOR UPDATE NOWAIT
                            """)) {
                connection.setAutoCommit(false);
                statement.setObject(1, endpointId);
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
        throw new AssertionError("Expected endpoint lock was not observed");
    }

    private int currentBackendPid() {
        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (backendPid == null) {
            throw new IllegalStateException("PostgreSQL backend PID was unavailable");
        }
        return backendPid;
    }

    private void awaitBackendLockWait(int backendPid) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE pid = ?
                          AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing PostgreSQL transaction did not wait on the expected lock");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency test was interrupted", exception);
        }
    }

    private List<WebhookDeliveryClaim> claim(CountDownLatch ready, CountDownLatch start, Instant now)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return claimService.claimDue(now, 1);
    }
}
