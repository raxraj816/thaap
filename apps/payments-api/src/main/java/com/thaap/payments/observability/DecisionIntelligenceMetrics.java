package com.thaap.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class DecisionIntelligenceMetrics {

    private static final Set<String> OPERATIONS = Set.of(
            "INVESTIGATION_DETAIL", "INVESTIGATION_SEARCH", "TIMELINE", "RELATED_PAYMENTS",
            "WEBHOOK_ACTIVITY", "SAVED_VIEW", "BULK_ACTION", "BULK_ITEMS",
            "DECISION_INSIGHTS", "REVIEW_INSIGHTS", "OPERATOR_INSIGHTS",
            "API_METADATA", "OPENAPI_ARTIFACT");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCEEDED", "EMPTY", "PARTIAL", "STALE", "INVALID", "UNAUTHENTICATED",
            "DENIED", "NOT_FOUND", "CONFLICT", "RATE_LIMITED", "TIMEOUT",
            "DEPENDENCY_UNAVAILABLE", "FAILED");
    private static final Set<String> VERSION_OUTCOMES = Set.of(
            "ABSENT", "EXACT", "MALFORMED", "UNSUPPORTED");
    private static final Set<String> SECTIONS = Set.of(
            "PAYMENT_LIFECYCLE", "DECISION_EXPLANATION", "REVIEW_AND_LABELS",
            "MONEY_AND_DISPUTES", "WEBHOOK_SUMMARY", "ANALYTICS_CONTEXT");
    private static final Set<String> SECTION_STATES = Set.of(
            "AVAILABLE", "EMPTY", "NOT_APPLICABLE", "PARTIAL", "STALE", "UNAVAILABLE", "DENIED");
    private static final Set<String> ITEM_RESULTS = Set.of(
            "SUCCEEDED", "STALE", "NOT_ELIGIBLE", "NOT_FOUND", "DENIED", "FAILED");
    private static final Set<String> LEASE_RESULTS = Set.of(
            "CLAIMED", "RECOVERED", "NOT_CLAIMABLE", "LAUNCH_FAILED", "TERMINAL");

    private final MeterRegistry registry;
    private final AtomicLong bulkBacklog = new AtomicLong();
    private final AtomicLong oldestBulkAgeSeconds = new AtomicLong();

    public DecisionIntelligenceMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("thaap.decision_intelligence.bulk.backlog", bulkBacklog);
        registry.gauge("thaap.decision_intelligence.bulk.oldest_age_seconds", oldestBulkAgeSeconds);
    }

    public void request(String operation, String outcome, long elapsedNanos) {
        require(OPERATIONS, operation, "operation");
        require(OUTCOMES, outcome, "outcome");
        Timer.builder("thaap.decision_intelligence.requests")
                .tag("operation", operation).tag("outcome", outcome)
                .publishPercentileHistogram().register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void queryResult(String operation, int resultSize, String coverage) {
        require(OPERATIONS, operation, "operation");
        require(Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE"), coverage, "coverage");
        String bucket = resultSize == 0 ? "ZERO" : resultSize <= 10 ? "ONE_TO_TEN"
                : resultSize <= 25 ? "ELEVEN_TO_TWENTY_FIVE" : "OVER_TWENTY_FIVE";
        registry.counter("thaap.decision_intelligence.query_results",
                "operation", operation, "size", bucket, "coverage", coverage).increment();
    }

    public void section(String section, String state) {
        require(SECTIONS, section, "section");
        require(SECTION_STATES, state, "state");
        registry.counter("thaap.decision_intelligence.investigation.sections",
                "section", section, "state", state).increment();
    }

    public void version(String outcome) {
        require(VERSION_OUTCOMES, outcome, "version outcome");
        registry.counter("thaap.decision_intelligence.api_version", "outcome", outcome).increment();
    }

    public void bulkItem(String result) {
        require(ITEM_RESULTS, result, "bulk item result");
        registry.counter("thaap.decision_intelligence.bulk.items", "result", result).increment();
    }

    public void bulkLease(String result) {
        require(LEASE_RESULTS, result, "bulk lease result");
        registry.counter("thaap.decision_intelligence.bulk.leases", "result", result).increment();
    }

    public void bulkBacklog(long count, Duration oldestAge) {
        bulkBacklog.set(Math.max(0, count));
        oldestBulkAgeSeconds.set(Math.max(0, oldestAge.toSeconds()));
    }

    private void require(Set<String> allowed, String value, String field) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Decision-intelligence metric " + field
                    + " is not allowlisted");
        }
    }
}
