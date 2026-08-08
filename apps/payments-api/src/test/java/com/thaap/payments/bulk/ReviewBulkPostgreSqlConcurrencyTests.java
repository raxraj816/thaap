package com.thaap.payments.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReviewBulkPostgreSqlConcurrencyTests extends ReviewBulkPostgreSqlTestBase {


    @Autowired private ReviewBulkActionStore store;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private RiskOperatorRepository operatorRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private Clock clock;


    @BeforeEach
    void clean() {
        databaseCleaner.clean();
    }

    @Test
    void forcedRowLockInterleavingLetsOnlyTheCommittedLiveLeaseWin() throws Exception {
        Fixture fixture = fixture("forced-claim");
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        UUID ownerToken = UUID.randomUUID();
        // The owner is an adversarial concurrent worker that must commit its lease using the
        // same application clock the production store uses. Reading CURRENT_TIMESTAMP here
        // would mix the database clock with the application-written created_at and violate
        // ck_review_bulk_action_requests_time whenever the two clocks drift.
        Instant ownerClaimedAt = clock.instant();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> owner = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM review_bulk_action_requests
                                WHERE id = ? FOR UPDATE
                                """, UUID.class, fixture.requestId());
                        rowLocked.countDown();
                        await(releaseOwner);
                        jdbcTemplate.update("""
                                UPDATE review_bulk_action_requests
                                SET lifecycle_status = 'RUNNING', lease_token = ?, lease_epoch = 1,
                                    lease_expires_at = ?, started_at = ?, updated_at = ?
                                WHERE id = ?
                                """, ownerToken, Timestamp.from(ownerClaimedAt.plusSeconds(120)),
                                Timestamp.from(ownerClaimedAt), Timestamp.from(ownerClaimedAt),
                                fixture.requestId());
                    }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ReviewBulkActionStore.Lease> contender = executor.submit(() ->
                    store.claim(fixture.requestId(), "contender"));
            awaitDatabaseLockWait("review_bulk_action_requests");
            releaseOwner.countDown();

            owner.get(5, TimeUnit.SECONDS);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isNull();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_token FROM review_bulk_action_requests WHERE id = ?
                """, UUID.class, fixture.requestId())).isEqualTo(ownerToken);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_epoch FROM review_bulk_action_requests WHERE id = ?
                """, Long.class, fixture.requestId())).isOne();
    }

    @Test
    void expiredLeaseReclaimsClaimedItemAndFencesStaleCompletionAgainstTerminalTruth() {
        Fixture fixture = fixture("lease-recovery");
        store.insertItem(UUID.randomUUID(), fixture.merchantId(), fixture.requestId(),
                UUID.randomUUID(), 0, 1, DatabaseInstant.now());
        ReviewBulkActionStore.Lease staleLease = store.claim(fixture.requestId(), "stale-worker");
        ReviewBulkActionStore.ClaimedItem staleItem = store.claimNext(staleLease);
        Instant expiredAt = clock.instant().minusSeconds(1);
        jdbcTemplate.update("""
                UPDATE review_bulk_action_requests
                SET lease_expires_at = ?, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(expiredAt), Timestamp.from(clock.instant()), fixture.requestId());

        ReviewBulkActionStore.Lease currentLease = store.claim(fixture.requestId(), "current-worker");
        ReviewBulkActionStore.ClaimedItem currentItem = store.claimNext(currentLease);
        assertThat(currentLease.epoch()).isEqualTo(staleLease.epoch() + 1);
        assertThat(currentItem.leaseEpoch()).isEqualTo(currentLease.epoch());
        assertThatThrownBy(() -> store.completeFailure(staleItem, "FAILED",
                "mutation_execution_failed", null))
                .hasMessageContaining("fence was lost")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        store.completeFailure(currentItem, "FAILED", "mutation_execution_failed", null);
        store.finalizeRequest(currentLease);
        assertThat(store.find(fixture.merchantId(), fixture.requestId()).lifecycleStatus())
                .isEqualTo("FAILED");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE review_bulk_action_items SET failure_code = 'altered'
                WHERE bulk_action_request_id = ?
                """, fixture.requestId()))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("immutable history");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM review_bulk_action_requests WHERE id = ?
                """, fixture.requestId()))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("cannot be deleted");
    }

    private Fixture fixture(String name) {
        Merchant merchant = merchantRepository.save(new Merchant(
                "bulk-postgres-" + name, "sk_test_" + UUID.randomUUID()));
        RiskOperator operator = operatorRepository.save(new RiskOperator(merchant.getId(),
                RiskOperatorRole.RISK_MANAGER, merchant.getApiKeyPrefix(), DatabaseInstant.now()));
        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.getId(), operator.getId(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, "a".repeat(64),
                "b".repeat(64), 1, DatabaseInstant.now());
        return new Fixture(merchant.getId(), requestId);
    }

    private void awaitDatabaseLockWait(String relation) throws InterruptedException {
        Instant deadline = DatabaseInstant.now().plusSeconds(5);
        while (DatabaseInstant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND query ILIKE ?
                    """, Integer.class, "%" + relation + "%");
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Expected a PostgreSQL lock wait on " + relation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deterministic release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(UUID merchantId, UUID requestId) {
    }
}
