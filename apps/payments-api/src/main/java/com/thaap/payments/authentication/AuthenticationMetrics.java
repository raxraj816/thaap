package com.thaap.payments.authentication;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AuthenticationMetrics {

    private final MeterRegistry registry;

    public AuthenticationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCreation(AuthenticationChallengeSource source) {
        afterTransaction("thaap.authentication.challenges.created", source.name(), "PENDING");
    }

    public void recordTerminal(AuthenticationChallengeSource source, AuthenticationResultCode result) {
        afterTransaction("thaap.authentication.challenges.completed", source.name(), result.name());
    }

    public void recordDenial(String reason) {
        registry.counter("thaap.authentication.completion.denied", "reason", reason).increment();
    }

    private void afterTransaction(String metric, String source, String result) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            registry.counter(metric, "source", source, "result", result,
                    "transaction", "committed").increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String transaction = status == TransactionSynchronization.STATUS_COMMITTED
                        ? "committed" : "rolled_back";
                registry.counter(metric, "source", source, "result", result,
                        "transaction", transaction).increment();
            }
        });
    }
}
