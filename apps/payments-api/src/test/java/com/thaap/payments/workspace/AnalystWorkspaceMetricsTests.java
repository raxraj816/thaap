package com.thaap.payments.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AnalystWorkspaceMetricsTests {

    @Test
    void committedAndRolledBackResultsUseOnlyClosedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalystWorkspaceMetrics metrics = new AnalystWorkspaceMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        metrics.afterCompletion("NOTE_RETENTION", "SUCCEEDED");
        TransactionSynchronization committed = TransactionSynchronizationManager
                .getSynchronizations().getFirst();
        TransactionSynchronizationManager.clearSynchronization();
        committed.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        TransactionSynchronizationManager.initSynchronization();
        metrics.afterCompletion("NOTE_REDACT", "SUCCEEDED");
        TransactionSynchronization rolledBack = TransactionSynchronizationManager
                .getSynchronizations().getFirst();
        TransactionSynchronizationManager.clearSynchronization();
        rolledBack.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(registry.get("thaap.analyst_workspace.operations")
                .tags("operation", "NOTE_RETENTION", "result", "SUCCEEDED")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("thaap.analyst_workspace.operations")
                .tags("operation", "NOTE_REDACT", "result", "ROLLED_BACK")
                .counter().count()).isEqualTo(1.0d);
        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toSet());
        assertThat(tagKeys).containsExactlyInAnyOrder("operation", "result");
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getType())
                .isEqualTo(Meter.Type.COUNTER));
        assertThatThrownBy(() -> metrics.increment("review-123", "SUCCEEDED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.increment("QUEUE_SEARCH", "merchant-123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.readinessFailure("key-v7"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
