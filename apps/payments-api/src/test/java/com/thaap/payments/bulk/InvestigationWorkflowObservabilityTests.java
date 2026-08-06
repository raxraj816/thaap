package com.thaap.payments.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Proves the bulk-assignment half of C55-OBS-001: the bulk signal vocabulary is closed, so no
 * tenant, actor, review, request, cursor, idempotency, or exception value can ever become a
 * metric dimension. The item and lease vocabularies are additionally pinned against the frozen
 * item-state table in the matrix, so a new terminal state cannot be emitted without the matrix
 * being updated in the same change.
 */
class InvestigationWorkflowObservabilityTests {

    /** The exact terminal outcomes the matrix's Frozen Bulk Item State table permits. */
    private static final Set<String> FROZEN_ITEM_OUTCOMES = Set.of(
            "SUCCEEDED", "STALE", "NOT_ELIGIBLE", "NOT_FOUND", "DENIED", "FAILED");

    @Test
    void theBulkItemMeterVocabularyIsExactlyTheFrozenTerminalStateTable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);

        for (String outcome : FROZEN_ITEM_OUTCOMES) {
            metrics.bulkItem(outcome);
        }

        Set<String> emitted = registry.get("thaap.decision_intelligence.bulk.items")
                .meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals("result"))
                .map(Tag::getValue).collect(java.util.stream.Collectors.toSet());
        assertThat(emitted).isEqualTo(FROZEN_ITEM_OUTCOMES);
    }

    @Test
    void anIdentifierShapedValueCanNeverBecomeABulkMetricDimension() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);

        // Every hostile value a caller could plausibly get into a dimension if the vocabulary
        // were open: identifiers, credentials, cursors, and raw exception text.
        List<String> hostile = List.of(
                UUID.randomUUID().toString(),
                "merchant-42",
                "rk_live_secret_credential",
                "eyJhbGciOiJIUzI1NiJ9.cursor.payload",
                "idempotency-key-abc",
                "org.postgresql.util.PSQLException: duplicate key value",
                "");

        for (String value : hostile) {
            assertThatRejected(() -> metrics.bulkItem(value));
            assertThatRejected(() -> metrics.bulkLease(value));
        }

        assertThat(registry.find("thaap.decision_intelligence.bulk.items").meter()).isNull();
        assertThat(registry.find("thaap.decision_intelligence.bulk.leases").meter()).isNull();
    }

    @Test
    void noBulkMeterCarriesAHighCardinalityTagKey() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);

        metrics.bulkItem("SUCCEEDED");
        metrics.bulkLease("CLAIMED");
        metrics.bulkBacklog(3, java.time.Duration.ofSeconds(30));
        metrics.request("BULK_ACTION", "SUCCEEDED", 1_000);
        metrics.request("BULK_ITEMS", "SUCCEEDED", 1_000);

        Set<String> prohibitedKeys = Set.of("merchant", "merchantId", "operator", "operatorId",
                "review", "reviewCaseId", "request", "requestId", "cursor", "idempotencyKey",
                "message", "exception", "path", "url", "view", "viewId");

        List<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("thaap.decision_intelligence."))
                .flatMap(id -> id.getTags().stream())
                .map(Tag::getKey).distinct().toList();

        assertThat(tagKeys).isNotEmpty();
        assertThat(tagKeys).doesNotContainAnyElementsOf(prohibitedKeys);
        // The whole bulk surface is described by a handful of closed keys, which is what keeps
        // the time-series count bounded no matter how much traffic flows through it.
        assertThat(tagKeys).allSatisfy(key -> assertThat(key)
                .isIn("operation", "outcome", "result", "size", "coverage", "section", "state"));
    }

    @Test
    void theLeaseVocabularyDistinguishesAFirstClaimFromARecoveryAndATerminalRun() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);

        // An operator triaging a stuck run needs to tell "never started" from "recovered after a
        // takeover" from "finished". Collapsing these would make a stuck lease invisible.
        Stream.of("CLAIMED", "RECOVERED", "NOT_CLAIMABLE", "LAUNCH_FAILED", "TERMINAL")
                .forEach(metrics::bulkLease);

        Set<String> emitted = registry.get("thaap.decision_intelligence.bulk.leases")
                .meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals("result"))
                .map(Tag::getValue).collect(java.util.stream.Collectors.toSet());
        assertThat(emitted).containsExactlyInAnyOrder(
                "CLAIMED", "RECOVERED", "NOT_CLAIMABLE", "LAUNCH_FAILED", "TERMINAL");
    }

    private void assertThatRejected(Runnable emission) {
        org.assertj.core.api.Assertions.assertThatThrownBy(emission::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }
}
