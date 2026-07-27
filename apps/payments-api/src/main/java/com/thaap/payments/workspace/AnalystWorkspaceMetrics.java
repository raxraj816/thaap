package com.thaap.payments.workspace;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AnalystWorkspaceMetrics {

    private static final Set<String> OPERATIONS = Set.of(
            "QUEUE_SEARCH", "SAVED_SEARCH_LIST", "SAVED_SEARCH_CREATE",
            "SAVED_SEARCH_UPDATE", "SAVED_SEARCH_DISABLE", "SAVED_SEARCH_EXECUTE",
            "NOTE_CREATE", "NOTE_CORRECT", "NOTE_REDACT", "NOTE_RETENTION");
    private static final Set<String> RESULTS = Set.of(
            "SUCCEEDED", "NO_DUE_NOTES", "ROLLED_BACK", "INVALID", "DENIED",
            "CONFLICT", "TIMEOUT", "UPGRADE_REQUIRED", "FAILED");
    private static final Set<String> READINESS_CAUSES = Set.of("NOTE_KEY_UNAVAILABLE");

    private final MeterRegistry registry;

    public AnalystWorkspaceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void afterCompletion(String operation, String successResult) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(operation, successResult);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(operation, status == TransactionSynchronization.STATUS_COMMITTED
                        ? successResult : "ROLLED_BACK");
            }
        });
    }

    public void increment(String operation, String result) {
        if (!OPERATIONS.contains(operation) || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("Analyst workspace metric tags are not allowlisted");
        }
        registry.counter("thaap.analyst_workspace.operations",
                "operation", operation, "result", result).increment();
    }

    public void readinessFailure(String cause) {
        if (!READINESS_CAUSES.contains(cause)) {
            throw new IllegalArgumentException("Analyst workspace readiness cause is not allowlisted");
        }
        registry.counter("thaap.analyst_workspace.readiness.failures", "cause", cause).increment();
    }
}
