package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.projection.rebuild.ProjectionRebuildControlService;
import com.thaap.payments.projection.rebuild.ProjectionRebuildExecutionProbe;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proof for {@code C52-CON-001}.
 *
 * <p>Forced failures at the integrated boundaries, with two tenants present throughout. The
 * chapter-level suites force each boundary in isolation; what this row adds is that the
 * convergent result still holds when the failure happens with another tenant's work sharing the
 * same tables, generations and locks.
 */
class PhaseFiveFailureSequenceIntegrationTests extends PhaseFiveClosureFixture {

    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ProjectionRebuildExecutionProbe executionProbe;

    @Test
    void aLeaseTakeoverMidRebuildConvergesOnOneResultWithBothTenantsIntact() {
        Map<String, String> firstBefore = sourceTruth(first.merchantId());
        Map<String, String> secondBefore = sourceTruth(second.merchantId());

        ProjectionRebuildControlService.RebuildView started = rebuildControlService.start(
                dataOperatorKey, "closure-takeover", ProjectionNames.PAYMENT_RISK, "ALL",
                "closure-takeover-request");

        AtomicInteger takeovers = new AtomicInteger();
        doAnswer(invocation -> {
            if (takeovers.getAndIncrement() == 0) {
                TransactionTemplate separateWorker = new TransactionTemplate(transactionManager);
                separateWorker.setPropagationBehavior(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                separateWorker.executeWithoutResult(status -> jdbcTemplate.update("""
                        UPDATE projection_rebuilds
                           SET lease_owner = ?, lease_token = ?, fencing_epoch = fencing_epoch + 1
                         WHERE id = ?
                        """, "closure-recovering-worker", UUID.randomUUID(),
                        started.rebuildId()));
            }
            return null;
        }).when(executionProbe).beforeChunkLease(any(), eq(1L));

        rebuildDispatcher.launchSynchronously(started.rebuildId());
        assertThat(takeovers.get()).isEqualTo(1);

        // The fenced worker wrote nothing for either tenant, and neither tenant's source moved.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections WHERE projection_generation_id = ?
                """, Long.class, started.generationId())).isZero();
        assertThat(sourceTruth(first.merchantId())).isEqualTo(firstBefore);
        assertThat(sourceTruth(second.merchantId())).isEqualTo(secondBefore);
        assertThat(ledgerBalance(first.merchantId())).isZero();
        assertThat(ledgerBalance(second.merchantId())).isZero();
    }

    @Test
    void activationAndCancellationCannotBothWinWhileBothTenantsAreBeingRebuilt() throws Exception {
        ProjectionRebuildControlService.RebuildView started = rebuildControlService.start(
                dataOperatorKey, "closure-activate-cancel", ProjectionNames.PAYMENT_RISK, "ALL",
                "closure-activate-cancel-request");
        rebuildDispatcher.launchSynchronously(started.rebuildId());
        assertThat(rebuildControlService.reconcile(dataOperatorKey, "closure-reconcile",
                ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                "closure-reconcile-request").passed()).isTrue();

        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger pauses = new AtomicInteger();
        doAnswer(invocation -> {
            if (pauses.getAndIncrement() == 0) {
                paused.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("activation was never released");
                }
            }
            return null;
        }).when(executionProbe).beforeGenerationActivation(any());

        UUID priorActive = activeGeneration();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectionRebuildControlService.ActivationView> activation =
                    executor.submit(() -> rebuildControlService.activate(dataOperatorKey,
                            "closure-activate", ProjectionNames.PAYMENT_RISK,
                            started.rebuildId(), "closure-activate-request"));
            assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> cancel = executor.submit(() -> {
                try {
                    rebuildControlService.cancel(dataOperatorKey, "closure-cancel",
                            ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                            "closure-cancel-request");
                    return null;
                } catch (Throwable refused) {
                    return refused;
                }
            });
            assertThatThrownBy(() -> cancel.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(activeGeneration()).isEqualTo(priorActive);

            release.countDown();
            assertThat(activation.get(15, TimeUnit.SECONDS).activated()).isTrue();
            assertThat(cancel.get(15, TimeUnit.SECONDS)).isNotNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(activeGeneration()).isEqualTo(started.generationId());
        assertThat(activeGenerationCount()).isEqualTo(1);
        // Both tenants are wholly present in the generation readers were switched onto.
        assertThat(projectedFor(started.generationId(), first.merchantId()))
                .isEqualTo(count("payment_intents", first.merchantId()));
        assertThat(projectedFor(started.generationId(), second.merchantId()))
                .isEqualTo(count("payment_intents", second.merchantId()));
    }

    private UUID activeGeneration() {
        return jdbcTemplate.queryForObject("""
                SELECT active_generation_id FROM projection_definitions
                WHERE projection_name = ?
                """, UUID.class, ProjectionNames.PAYMENT_RISK);
    }

    private int activeGenerationCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_generations generation
                JOIN projection_definitions definition
                  ON definition.id = generation.projection_definition_id
                WHERE definition.projection_name = ?
                  AND generation.lifecycle_status = 'ACTIVE'
                """, Integer.class, ProjectionNames.PAYMENT_RISK);
    }

    private long projectedFor(UUID generationId, UUID merchantId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ? AND merchant_id = ?
                """, Long.class, generationId, merchantId);
    }
}
