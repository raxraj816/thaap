package com.thaap.payments.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.security.CorrelationValueHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
class ResourceCreationIdempotencyPostgreSqlConcurrencyTests {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private MerchantCreationService merchantCreationService;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantCreationRecordRepository merchantCreationRecordRepository;

    @Autowired
    private MerchantCreationIdentityRepository merchantCreationIdentityRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private SensitiveValueHasher sensitiveValueHasher;

    @Autowired
    private CorrelationValueHasher correlationValueHasher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("thaap.idempotency.reservation-lease", () -> "100ms");
    }

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void concurrentMerchantCreationWaitsAndReplaysOneMerchantAndApiKey() throws Exception {
        CountDownLatch firstOwnsCompletionLock = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        AtomicReference<MerchantCreationService.MerchantCreationResult> firstResult =
                new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    firstResult.set(merchantCreationService.create(
                            "postgres-merchant-create", "PostgreSQL Merchant"));
                    firstOwnsCompletionLock.countDown();
                    await(releaseFirstCommit);
                });
                return null;
            });

            assertThat(firstOwnsCompletionLock.await(5, TimeUnit.SECONDS)).isTrue();
            UUID recordId = jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM merchant_creation_records
                    """, UUID.class);
            awaitRowLockConflict("merchant_creation_records", recordId);

            Future<MerchantCreationService.MerchantCreationResult> second = executor.submit(() ->
                    merchantCreationService.create(
                            "postgres-merchant-create", "PostgreSQL Merchant"));
            awaitTableLockWait("merchant_creation_records");

            releaseFirstCommit.countDown();
            first.get(5, TimeUnit.SECONDS);
            MerchantCreationService.MerchantCreationResult replayed =
                    second.get(5, TimeUnit.SECONDS);

            assertThat(replayed.merchant().getId())
                    .isEqualTo(firstResult.get().merchant().getId());
            assertThat(replayed.rawApiKey()).isEqualTo(firstResult.get().rawApiKey());
            assertThat(merchantRepository.count()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM merchant_creation_records", Integer.class))
                    .isEqualTo(1);
        } finally {
            releaseFirstCommit.countDown();
        }
    }

    @Test
    void rollingPepperVersionsShareOneDatabaseIdentityAndReplayOneMerchant() throws Exception {
        String firstPepper = "rolling-pepper-v1";
        String secondPepper = "rolling-pepper-v2";
        String firstEncoded = Base64.getEncoder().encodeToString(
                firstPepper.getBytes(StandardCharsets.UTF_8));
        String secondEncoded = Base64.getEncoder().encodeToString(
                secondPepper.getBytes(StandardCharsets.UTF_8));
        MerchantCreationService versionTwoService = merchantCreationService(
                new SensitiveValueHasher(
                        secondPepper, "rolling-v2", "rolling-v1=" + firstEncoded));
        MerchantCreationService versionOneService = merchantCreationService(
                new SensitiveValueHasher(
                        firstPepper, "rolling-v1", "rolling-v2=" + secondEncoded));
        CountDownLatch firstOwnsCompletionLock = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        AtomicReference<MerchantCreationService.MerchantCreationResult> firstResult =
                new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    firstResult.set(versionTwoService.create(
                            "rolling-version-key", "Rolling Version Merchant"));
                    firstOwnsCompletionLock.countDown();
                    await(releaseFirstCommit);
                });
                return null;
            });

            assertThat(firstOwnsCompletionLock.await(5, TimeUnit.SECONDS)).isTrue();
            UUID recordId = jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM merchant_creation_records
                    """, UUID.class);
            awaitRowLockConflict("merchant_creation_records", recordId);

            Future<MerchantCreationService.MerchantCreationResult> second =
                    executor.submit(() -> versionOneService.create(
                            "rolling-version-key", "Rolling Version Merchant"));
            awaitTableLockWait("merchant_creation_records");

            releaseFirstCommit.countDown();
            first.get(5, TimeUnit.SECONDS);
            MerchantCreationService.MerchantCreationResult replayed =
                    second.get(5, TimeUnit.SECONDS);

            assertThat(replayed.merchant().getId())
                    .isEqualTo(firstResult.get().merchant().getId());
            assertThat(replayed.rawApiKey()).isEqualTo(firstResult.get().rawApiKey());
            assertThat(merchantRepository.count()).isEqualTo(1);
            assertThat(merchantCreationRecordRepository.count()).isEqualTo(1);
            assertThat(merchantCreationIdentityRepository.count()).isEqualTo(2);
        } finally {
            releaseFirstCommit.countDown();
        }
    }

    @Test
    void expiredCustomerLeaseCannotBeStolenWhileMutationOwnsTheReservationLock() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("customer-concurrency", "thaap_test_customer_concurrency"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix());
        CustomerRequest request = new CustomerRequest("customer-concurrency@example.test");
        CountDownLatch firstMutationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstMutation = new CountDownLatch(1);
        AtomicInteger duplicateSupplierCalls = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<CustomerResult>> first = executor.submit(() ->
                    idempotencyService.execute(
                            merchantContext, "postgres-customer-create",
                            "customers.create", request, CustomerResult.class,
                            () -> {
                                firstMutationStarted.countDown();
                                await(releaseFirstMutation);
                                Customer customer = customerRepository.save(new Customer(
                                        merchantContext.merchantId(),
                                        correlationValueHasher.hmacSha256Lowercase(
                                                "customer.email", request.email()),
                                        null));
                                return ResponseEntity.status(HttpStatus.CREATED)
                                        .body(new CustomerResult(
                                                customer.getId(), customer.getMerchantId()));
                            }));

            assertThat(firstMutationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            UUID recordId = jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM idempotency_records
                    WHERE merchant_id = ?
                      AND idempotency_key = ?
                    """, UUID.class, merchant.getId(), "postgres-customer-create");
            awaitRowLockConflict("idempotency_records", recordId);
            awaitLeaseExpiry(recordId);

            Future<ResponseEntity<CustomerResult>> second = executor.submit(() ->
                    createCustomer(merchantContext, "postgres-customer-create",
                            request, duplicateSupplierCalls));
            awaitTableLockWait("idempotency_records");

            releaseFirstMutation.countDown();
            ResponseEntity<CustomerResult> firstResult = first.get(5, TimeUnit.SECONDS);
            ResponseEntity<CustomerResult> replayed = second.get(5, TimeUnit.SECONDS);

            assertThat(replayed.getBody()).isEqualTo(firstResult.getBody());
            assertThat(duplicateSupplierCalls).hasValue(0);
            assertThat(customerRepository.count()).isEqualTo(1);
        } finally {
            releaseFirstMutation.countDown();
        }
    }

    private void awaitLeaseExpiry(UUID recordId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean expired = jdbcTemplate.queryForObject("""
                    SELECT lease_expires_at <= CURRENT_TIMESTAMP
                    FROM idempotency_records
                    WHERE id = ?
                    """, Boolean.class, recordId);
            if (Boolean.TRUE.equals(expired)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected idempotency reservation lease to expire");
    }

    private ResponseEntity<CustomerResult> createCustomer(
            MerchantContext merchantContext, String idempotencyKey,
            CustomerRequest request, AtomicInteger supplierCalls) {
        return idempotencyService.execute(
                merchantContext, idempotencyKey, "customers.create", request,
                CustomerResult.class, () -> {
                    supplierCalls.incrementAndGet();
                    Customer customer = customerRepository.save(new Customer(
                            merchantContext.merchantId(),
                            correlationValueHasher.hmacSha256Lowercase(
                                    "customer.email", request.email()),
                            null));
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(new CustomerResult(customer.getId(), customer.getMerchantId()));
                });
    }

    private void awaitRowLockConflict(String tableName, UUID recordId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT id FROM " + tableName + " WHERE id = ? FOR UPDATE NOWAIT")) {
                connection.setAutoCommit(false);
                statement.setObject(1, recordId);
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
        throw new AssertionError("Expected creation idempotency row lock was not observed");
    }

    private void awaitTableLockWait(String tableName) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock'
                          AND query LIKE ?
                    )
                    """, Boolean.class, "%" + tableName + "%");
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing creation request did not wait on " + tableName);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for creation idempotency latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Creation idempotency concurrency test was interrupted", exception);
        }
    }

    private MerchantCreationService merchantCreationService(
            SensitiveValueHasher hasher) {
        return new MerchantCreationService(
                merchantRepository,
                merchantCreationRecordRepository,
                merchantCreationIdentityRepository,
                entityManager,
                hasher,
                clock,
                transactionManager,
                Duration.ofMillis(100));
    }

    private record CustomerRequest(String email) {
    }

    private record CustomerResult(UUID id, UUID merchantId) {
    }
}
