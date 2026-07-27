package com.thaap.payments.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class MerchantRiskMonitoringMetrics {

    private final MeterRegistry meterRegistry;

    public MerchantRiskMonitoringMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRead(MerchantRiskMetricCode code, MonitoringCoverageStatus coverage) {
        meterRegistry.counter("thaap.risk.monitoring.reads",
                "metric", code == null ? "SUMMARY" : code.name(),
                "coverage", coverage == null ? "MULTIPLE" : coverage.name()).increment();
    }

    public void recordRequest(String operation, String result) {
        meterRegistry.counter("thaap.risk.monitoring.requests",
                "operation", operation, "result", result).increment();
    }

    public void recordSnapshotAfterCompletion(String outcome) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            incrementSnapshot(outcome, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                incrementSnapshot(outcome, status == TransactionSynchronization.STATUS_COMMITTED
                        ? "committed" : "rolled_back");
            }
        });
    }

    private void incrementSnapshot(String outcome, String transaction) {
        meterRegistry.counter("thaap.risk.monitoring.snapshots",
                "outcome", outcome, "transaction", transaction).increment();
    }
}
