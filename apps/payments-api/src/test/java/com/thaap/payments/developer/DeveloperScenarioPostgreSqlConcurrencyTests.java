package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
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
@Import(DeveloperScenarioPostgreSqlConcurrencyTests.MutableClockConfiguration.class)
class DeveloperScenarioPostgreSqlConcurrencyTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private DeveloperScenarioService service;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableClock clock;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("thaap.developer-scenarios.lease-duration", () -> "45s");
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE developer_scenario_events, developer_scenario_steps, "
                + "developer_scenario_runs, audit_logs, idempotency_records, merchants CASCADE");
        clock.set(Instant.parse("2026-07-27T00:00:00Z"));
    }

    @Test
    void forcedConcurrentClaimsWaitOnRunRowAndCommitOneLiveOwner() throws Exception {
        MerchantContext context = createContext("forced-claim");
        DeveloperScenarioService.RunView run = service.create(context,
                DeveloperScenarioType.APPROVED_PAYMENT, "forced-claim-seed", "request-create");
        UUID ownerId = UUID.randomUUID();
        UUID contenderId = UUID.randomUUID();
        CountDownLatch ownerClaimed = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DeveloperScenarioService.RunView> owner = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        DeveloperScenarioService.RunView claimed = service.claim(
                                context, run.id(), ownerId, "request-owner");
                        ownerClaimed.countDown();
                        await(releaseOwner);
                        return claimed;
                    }));
            assertThat(ownerClaimed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DeveloperScenarioService.RunView> contender = executor.submit(() ->
                    service.claim(context, run.id(), contenderId, "request-contender"));
            awaitDatabaseLockWait("developer_scenario_runs");
            releaseOwner.countDown();

            assertThat(owner.get(5, TimeUnit.SECONDS).leaseOwner()).isEqualTo(ownerId);
            assertThatThrownBy(() -> contender.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(ResponseStatusException.class)
                    .rootCause().extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_owner FROM developer_scenario_runs WHERE id = ?
                """, UUID.class, run.id())).isEqualTo(ownerId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM developer_scenario_events
                 WHERE scenario_run_id = ? AND event_type = 'LEASE_CLAIMED'
                """, Integer.class, run.id())).isOne();
    }

    @Test
    void expiredLeaseAdvancesEpochAndRejectsStaleWorkerWhileHistoryTriggersHold() {
        MerchantContext context = createContext("stale-lease");
        DeveloperScenarioService.RunView run = service.create(context,
                DeveloperScenarioType.ISSUER_DECLINE, "stale-lease-seed", "request-create");
        UUID staleWorker = UUID.randomUUID();
        DeveloperScenarioService.RunView first = service.claim(
                context, run.id(), staleWorker, "request-first");
        clock.advance(Duration.ofSeconds(46));
        UUID currentWorker = UUID.randomUUID();
        DeveloperScenarioService.RunView reclaimed = service.claim(
                context, run.id(), currentWorker, "request-reclaim");

        assertThat(reclaimed.leaseEpoch()).isEqualTo(first.leaseEpoch() + 1);
        assertThat(reclaimed.leaseOwner()).isEqualTo(currentWorker);
        assertThatThrownBy(() -> service.completeStep(context, run.id(), 0,
                staleWorker, first.leaseEpoch(), UUID.randomUUID(), "request-stale"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT next_step_index FROM developer_scenario_runs WHERE id = ?
                """, Integer.class, run.id())).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE developer_scenario_events SET outcome_code = 'ALTERED'
                 WHERE scenario_run_id = ?
                """, run.id())).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM developer_scenario_runs WHERE id = ?
                """, run.id())).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cannot be deleted");
    }

    private MerchantContext createContext(String name) {
        Merchant merchant = merchantRepository.save(new Merchant(name, "sk_test_" + UUID.randomUUID()));
        return new MerchantContext(merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix(),
                TrafficOrigin.SYNTHETIC_SCENARIO);
    }

    private void awaitDatabaseLockWait(String relation) throws InterruptedException {
        Instant deadline = DatabaseInstant.now().plusSeconds(5);
        while (DatabaseInstant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'
                       AND query ILIKE ?
                    """, Integer.class, "%" + relation + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Expected a PostgreSQL lock wait on " + relation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deterministic test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-07-27T00:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            instant.set(value);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
