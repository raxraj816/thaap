package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proof for {@code C46-OBS-001}.
 *
 * <p>The meter this asserts has been emitted since risk-operation events existed; nothing asserted it, so
 * the row closed on a suite that was never written ({@code ER-0099}, {@code TD-0007}).
 * The property that matters is not that a counter moves — it is that an operator can
 * tell a committed risk operation from a rolled-back one, and that no tag carries tenant,
 * actor, resource or credential identity into a metrics backend.
 */
class RiskOperationsEventObservabilityTests {

    private static final String METRIC_NAME = "thaap.risk.operations";
    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("operation", "outcome", "transaction");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RiskOperationsMetrics metrics = new RiskOperationsMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aCommittedOperationIsInvisibleUntilTheTransactionActuallyCommits() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordCommitted("review_resolution", "resolved");

        assertThat(registry.find(METRIC_NAME).counters()).isEmpty();

        completeWith(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(registry.get(METRIC_NAME)
                .tags("operation", "review_resolution", "outcome", "resolved",
                        "transaction", "committed")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void aRolledBackOperationIsReportedAsRolledBackRatherThanDisappearing() {
        // The failure this catches is a metric that only counts successes: a rolled-back
        // risk operation would then be indistinguishable from one that never happened.
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordCommitted("label_supersession", "superseded");
        completeWith(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(registry.get(METRIC_NAME)
                .tags("operation", "label_supersession", "outcome", "superseded",
                        "transaction", "rolled_back")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.find(METRIC_NAME)
                .tags("transaction", "committed").counters()).isEmpty();
    }

    @Test
    void everyFrozenMutationFamilyReportsUnderOneBoundedTagSet() {
        TransactionSynchronizationManager.initSynchronization();

        for (String operation : new String[] {
                "review_resolution", "fraud_label", "card_testing_incident",
                "governed_control", "dispute_operation", "webhook_operation",
                "post_authorization_signal", "developer_scenario_run"}) {
            metrics.recordCommitted(operation, "committed");
        }
        completeWith(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(registry.find(METRIC_NAME).counters()).hasSize(8);
        assertThat(registry.getMeters()).allSatisfy(this::assertBoundedAndNonDisclosing);
    }

    @Test
    void anOperationOutsideATransactionStillReportsRatherThanVanishing() {
        // Not every risk operation runs inside a transaction. Silently dropping the
        // meter there would leave an operator unable to see the operation at all.
        metrics.recordCommitted("scenario_lease", "claimed");

        assertThat(registry.get(METRIC_NAME)
                .tags("operation", "scenario_lease", "outcome", "claimed",
                        "transaction", "committed")
                .counter().count()).isEqualTo(1.0d);
    }

    private void completeWith(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(status));
    }

    private void assertBoundedAndNonDisclosing(Meter meter) {
        assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_TAG_KEYS);
        assertThat(meter.getId().getTags()).extracting(Tag::getValue)
                .allSatisfy(value -> assertThat(value).doesNotContain(
                        "merchant-canary", "actor-canary", "review-canary",
                        "label-canary", "credential-canary", "note-canary"));
    }
}
