package com.thaap.payments.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PlatformOperatorPostgreSqlTests {

    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private PlatformOperatorService operatorService;
    @Autowired private PlatformOperatorRepository operatorRepository;
    @Autowired private PlatformOperatorBootstrapStateRepository bootstrapStateRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleaner databaseCleaner;

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
    void concurrentExactBootstrapCreatesOneAdministratorAndRevealsOneCredential()
            throws Exception {
        CountDownLatch holderOwnsBootstrapLock = new CountDownLatch(1);
        CountDownLatch releaseBootstrapLock = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<Void> holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    bootstrapStateRepository.findForUpdate().orElseThrow();
                    holderOwnsBootstrapLock.countDown();
                    await(releaseBootstrapLock, "bootstrap lock release");
                });
                return null;
            });
            assertThat(holderOwnsBootstrapLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<PlatformOperatorService.MutationResult> first = executor.submit(() ->
                    operatorService.bootstrap(BOOTSTRAP_TOKEN, "concurrent-bootstrap",
                            "Founding administrator", "bootstrap-request-first"));
            Future<PlatformOperatorService.MutationResult> second = executor.submit(() ->
                    operatorService.bootstrap(BOOTSTRAP_TOKEN, "concurrent-bootstrap",
                            "Founding administrator", "bootstrap-request-second"));

            awaitPostgreSqlLockWait("platform_operator_bootstrap_state", 2);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            releaseBootstrapLock.countDown();
            holder.get(5, TimeUnit.SECONDS);
            PlatformOperatorService.MutationResult firstResult = first.get(5, TimeUnit.SECONDS);
            PlatformOperatorService.MutationResult secondResult = second.get(5, TimeUnit.SECONDS);

            List<PlatformOperatorService.MutationResult> results = List.of(
                    firstResult, secondResult);
            assertThat(results).extracting(PlatformOperatorService.MutationResult::operator)
                    .extracting(PlatformOperator::getId)
                    .containsOnly(firstResult.operator().getId());
            assertThat(results).filteredOn(
                            PlatformOperatorService.MutationResult::credentialRevealed)
                    .singleElement().satisfies(result -> {
                        assertThat(result.replayed()).isFalse();
                        assertThat(result.credential()).startsWith("thaap_platform_");
                    });
            assertThat(results).filteredOn(PlatformOperatorService.MutationResult::replayed)
                    .singleElement().satisfies(result -> {
                        assertThat(result.credentialRevealed()).isFalse();
                        assertThat(result.credential()).isNull();
                    });

            PlatformOperatorService.MutationResult winner = results.stream()
                    .filter(PlatformOperatorService.MutationResult::credentialRevealed)
                    .findFirst().orElseThrow();
            String rawCredential = winner.credential();
            PlatformOperatorService.MutationResult laterReplay = operatorService.bootstrap(
                    BOOTSTRAP_TOKEN, "concurrent-bootstrap", "Founding administrator",
                    "bootstrap-request-later-replay");

            assertThat(laterReplay.replayed()).isTrue();
            assertThat(laterReplay.credentialRevealed()).isFalse();
            assertThat(laterReplay.credential()).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_operators", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_operator_credentials", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_operator_requests", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM audit_logs
                    WHERE actor_type = 'PLATFORM_BOOTSTRAP'
                      AND merchant_id IS NULL
                      AND action = 'platform_operator.bootstrapped'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT secret_sha256 FROM platform_operator_credentials", String.class))
                    .isEqualTo(SecretHasher.sha256(rawCredential));
            assertThat(countPlaintextOccurrences(rawCredential)).isZero();
        } finally {
            releaseBootstrapLock.countDown();
        }
    }

    @Test
    void databaseGuardsPermitOneWayDisableAndRejectHistoryRewrites() {
        PlatformOperatorService.MutationResult administrator = bootstrapAdministrator(
                "lifecycle-admin");
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administrator.credential(), "lifecycle-create", "Lifecycle data operator",
                PlatformOperatorRole.DATA_OPERATOR, "lifecycle-create-request");

        PlatformOperatorService.MutationResult disabled = operatorService.disable(
                administrator.credential(), "lifecycle-disable", dataOperator.operator().getId(),
                "lifecycle-disable-request");
        UUID operatorId = disabled.operator().getId();

        assertThat(disabled.operator().getStatus()).isEqualTo(PlatformOperatorStatus.DISABLED);
        assertThat(disabled.operator().getLifecycleEpoch()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_operator_credentials
                WHERE platform_operator_id = ? AND disabled_at IS NOT NULL
                """, Integer.class, operatorId)).isEqualTo(1);

        assertDatabaseRejects(() -> jdbcTemplate.update("""
                UPDATE platform_operators SET display_name = 'Rewritten history'
                WHERE id = ?
                """, operatorId), "platform operator lifecycle mutation is invalid");
        assertDatabaseRejects(() -> jdbcTemplate.update("""
                UPDATE platform_operators
                SET status = 'ACTIVE', lifecycle_epoch = lifecycle_epoch + 1,
                    disabled_at = NULL
                WHERE id = ?
                """, operatorId), "platform operator lifecycle mutation is invalid");
        assertDatabaseRejects(() -> jdbcTemplate.update(
                "DELETE FROM platform_operators WHERE id = ?", operatorId),
                "platform operator history cannot be deleted");
        assertDatabaseRejects(() -> jdbcTemplate.update("""
                UPDATE platform_operator_credentials
                SET secret_sha256 = ? WHERE platform_operator_id = ?
                """, "b".repeat(64), operatorId),
                "platform credential lifecycle mutation is invalid");
        assertDatabaseRejects(() -> jdbcTemplate.update(
                "DELETE FROM platform_operator_credentials WHERE platform_operator_id = ?",
                operatorId), "platform credential history cannot be deleted");
        assertDatabaseRejects(() -> jdbcTemplate.update("""
                UPDATE platform_operator_requests SET request_digest = ?
                WHERE result_operator_id = ?
                """, "c".repeat(64), operatorId), "domain event history is immutable");
        assertDatabaseRejects(() -> jdbcTemplate.update("""
                UPDATE platform_operator_bootstrap_state
                SET bootstrap_completed = FALSE, first_admin_id = NULL
                WHERE singleton_id = 1
                """), "platform bootstrap state cannot regress");
    }

    @Test
    void queuedActorMutationRevalidatesLiveStatusAfterPostgreSqlLockAcquisition()
            throws Exception {
        PlatformOperatorService.MutationResult primaryAdministrator = bootstrapAdministrator(
                "race-primary-admin");
        PlatformOperatorService.MutationResult secondaryAdministrator = operatorService.create(
                primaryAdministrator.credential(), "race-create-secondary",
                "Secondary administrator", PlatformOperatorRole.PLATFORM_ADMIN,
                "race-create-secondary-request");
        UUID secondaryId = secondaryAdministrator.operator().getId();

        CountDownLatch holderOwnsSecondaryLock = new CountDownLatch(1);
        CountDownLatch releaseSecondaryLock = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<Void> holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    operatorRepository.findByIdForUpdate(secondaryId).orElseThrow();
                    holderOwnsSecondaryLock.countDown();
                    await(releaseSecondaryLock, "secondary operator lock release");
                });
                return null;
            });
            assertThat(holderOwnsSecondaryLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<PlatformOperatorService.MutationResult> disable = executor.submit(() ->
                    operatorService.disable(primaryAdministrator.credential(),
                            "race-disable-secondary", secondaryId,
                            "race-disable-secondary-request"));
            awaitPostgreSqlLockWait("platform_operators", 1);

            Future<PlatformOperatorService.MutationResult> staleCreate = executor.submit(() ->
                    operatorService.create(secondaryAdministrator.credential(),
                            "race-stale-create", "Raced data operator",
                            PlatformOperatorRole.DATA_OPERATOR, "race-stale-create-request"));
            awaitPostgreSqlLockWait("platform_operators", 2);
            assertThat(disable.isDone()).isFalse();
            assertThat(staleCreate.isDone()).isFalse();

            releaseSecondaryLock.countDown();
            holder.get(5, TimeUnit.SECONDS);
            PlatformOperatorService.MutationResult disabled = disable.get(5, TimeUnit.SECONDS);
            Throwable staleFailure = catchThrowable(() -> staleCreate.get(5, TimeUnit.SECONDS));

            assertThat(disabled.operator().getStatus())
                    .isEqualTo(PlatformOperatorStatus.DISABLED);
            assertThat(staleFailure).isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(ResponseStatusException.class);
            ResponseStatusException rejection = (ResponseStatusException) rootCause(staleFailure);
            assertThat(rejection.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM platform_operators
                    WHERE display_name = 'Raced data operator'
                    """, Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM platform_operator_requests
                    WHERE operation = 'CREATE_OPERATOR'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM audit_logs
                    WHERE action = 'platform_operator.created'
                    """, Integer.class)).isEqualTo(1);
        } finally {
            releaseSecondaryLock.countDown();
        }
    }

    @Test
    void platformIdentityAndAuditStateCannotEnterMerchantTenantDomain() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("tenant-boundary-merchant", "tenant-boundary-api-key"));
        UUID merchantAuditId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    id, merchant_id, actor_type, actor_id, action, aggregate_type,
                    aggregate_id, request_id, created_at)
                VALUES (?, ?, 'MERCHANT_API_KEY', 'tenant_key_', 'tenant.baseline',
                    'merchant', ?, 'tenant-baseline-request', CURRENT_TIMESTAMP)
                """, merchantAuditId, merchant.getId(), merchant.getId());
        Map<String, Object> merchantBefore = jdbcTemplate.queryForMap("""
                SELECT id, name, status, api_key_prefix, api_key_hash, created_at
                FROM merchants WHERE id = ?
                """, merchant.getId());

        PlatformOperatorService.MutationResult administrator = bootstrapAdministrator(
                "tenant-boundary-admin");
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administrator.credential(), "tenant-boundary-create", "Tenant-free data operator",
                PlatformOperatorRole.DATA_OPERATOR, "tenant-boundary-create-request");
        operatorService.disable(administrator.credential(), "tenant-boundary-disable",
                dataOperator.operator().getId(), "tenant-boundary-disable-request");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT id, name, status, api_key_prefix, api_key_hash, created_at
                FROM merchants WHERE id = ?
                """, merchant.getId())).isEqualTo(merchantBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE id = ?", Integer.class,
                merchantAuditId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name LIKE 'platform_operator%'
                  AND column_name = 'merchant_id'
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_type IN ('PLATFORM_BOOTSTRAP', 'PLATFORM_OPERATOR')
                  AND merchant_id IS NULL
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_type IN ('PLATFORM_BOOTSTRAP', 'PLATFORM_OPERATOR')
                  AND merchant_id IS NOT NULL
                """, Integer.class)).isZero();

        assertDatabaseRejects(() -> jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    id, merchant_id, actor_type, actor_id, action, aggregate_type,
                    aggregate_id, request_id, created_at)
                VALUES (?, ?, 'PLATFORM_OPERATOR', 'pop_illegal', 'illegal.scope',
                    'platform_operator', ?, 'illegal-platform-scope', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchant.getId(), UUID.randomUUID()),
                "ck_audit_logs_actor_scope");
        assertDatabaseRejects(() -> jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    id, merchant_id, actor_type, actor_id, action, aggregate_type,
                    aggregate_id, request_id, created_at)
                VALUES (?, NULL, 'MERCHANT_API_KEY', 'tenant_key_', 'illegal.scope',
                    'merchant', ?, 'illegal-merchant-scope', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchant.getId()), "ck_audit_logs_actor_scope");
    }

    private PlatformOperatorService.MutationResult bootstrapAdministrator(String key) {
        return operatorService.bootstrap(BOOTSTRAP_TOKEN, key, "Primary administrator",
                key + "-request");
    }

    private int countPlaintextOccurrences(String rawCredential) {
        Integer matches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT concat_ws('|', id::text, operator_code, display_name, role,
                        status, lifecycle_epoch::text, created_by_operator_id::text,
                        created_at::text, disabled_at::text) AS stored_text
                    FROM platform_operators
                    UNION ALL
                    SELECT concat_ws('|', id::text, platform_operator_id::text,
                        secret_prefix, secret_sha256, secret_identity_hmac,
                        secret_hmac_key_version, created_at::text, disabled_at::text)
                    FROM platform_operator_credentials
                    UNION ALL
                    SELECT concat_ws('|', id::text, principal_kind,
                        principal_operator_id::text, principal_credential_id::text,
                        principal_credential_prefix, operation, request_identity_hmac,
                        request_identity_key_version, request_digest,
                        request_digest_key_version, lifecycle_status,
                        result_operator_id::text, created_at::text, completed_at::text)
                    FROM platform_operator_requests
                    UNION ALL
                    SELECT concat_ws('|', id::text, merchant_id::text, actor_type, actor_id,
                        action, aggregate_type, aggregate_id::text, request_id, trace_id,
                        reason, before_json, after_json, created_at::text)
                    FROM audit_logs
                ) persisted
                WHERE position(? in stored_text) > 0
                """, Integer.class, rawCredential);
        return matches == null ? 0 : matches;
    }

    private void awaitPostgreSqlLockWait(String tableName, int expectedWaiters)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String queryPattern = "%" + tableName + "%";
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE ?
                    """, Integer.class, queryPattern);
            if (waiters != null && waiters >= expectedWaiters) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + expectedWaiters
                + " PostgreSQL lock waiter(s) on " + tableName);
    }

    private void assertDatabaseRejects(Runnable mutation, String expectedMessage) {
        assertThatThrownBy(mutation::run)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(expectedMessage);
    }

    private void await(CountDownLatch latch, String reason) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + reason);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + reason, exception);
        }
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
