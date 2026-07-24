package com.thaap.payments.dispute;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ChargebackMetrics {
    private final MeterRegistry registry;

    public ChargebackMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordIngestion(ChargebackReasonCode reasonCode, ChargebackStatus allocation, String result) {
        afterCompletion("thaap.dispute.chargeback.ingestions",
                new String[]{"reason_class", reasonCode.isFraud() ? "FRAUD" : "NON_FRAUD",
                        "allocation", allocation.name(), "result", result});
    }

    public void recordEvidence(String result) {
        afterCompletion("thaap.dispute.evidence.packets", new String[]{"result", result});
    }

    private void afterCompletion(String name, String[] tags) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(name, tags, "committed");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                increment(name, tags, status == TransactionSynchronization.STATUS_COMMITTED
                        ? "committed" : "rolled_back");
            }
        });
    }

    private void increment(String name, String[] tags, String transaction) {
        String[] tagged = java.util.Arrays.copyOf(tags, tags.length + 2);
        tagged[tags.length] = "transaction";
        tagged[tags.length + 1] = transaction;
        registry.counter(name, tagged).increment();
    }
}
