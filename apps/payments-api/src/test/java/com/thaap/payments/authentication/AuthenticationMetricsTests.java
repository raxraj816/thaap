package com.thaap.payments.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthenticationMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuthenticationMetrics metrics = new AuthenticationMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void terminalMetricsAppearOnlyAfterCommitWithClosedDimensions() {
        TransactionSynchronizationManager.initSynchronization();
        metrics.recordTerminal(AuthenticationChallengeSource.FRAUD_ENGINE,
                AuthenticationResultCode.AUTHENTICATED);

        assertThat(registry.find("thaap.authentication.challenges.completed").counters()).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        assertThat(registry.get("thaap.authentication.challenges.completed")
                .tags("source", "FRAUD_ENGINE", "result", "AUTHENTICATED", "transaction", "committed")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters().getFirst().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrderElementsOf(Set.of("source", "result", "transaction"));
    }

    @Test
    void rollbackAndDenialAreDistinguishableWithoutResourceIdentifiers() {
        TransactionSynchronizationManager.initSynchronization();
        metrics.recordCreation(AuthenticationChallengeSource.PROCESSOR);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        metrics.recordDenial("SECRET_MISMATCH");

        assertThat(registry.get("thaap.authentication.challenges.created")
                .tags("source", "PROCESSOR", "result", "PENDING", "transaction", "rolled_back")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("thaap.authentication.completion.denied")
                .tag("reason", "SECRET_MISMATCH").counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getValue())
                                .doesNotContain("authc_", "payment", "challenge")));
    }
}
