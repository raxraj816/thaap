package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class VelocityRiskCoordinatorTests {

    private final RedisVelocityService redis = mock(RedisVelocityService.class);
    private final VelocityIdentityFactory identityFactory = mock(VelocityIdentityFactory.class);
    private final VelocityObservationRecordRepository repository = mock(VelocityObservationRecordRepository.class);
    private final VelocityMetrics metrics = mock(VelocityMetrics.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-13T18:30:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordsUnavailableEvidenceInsteadOfFabricatingZero() {
        Fixture fixture = fixture();
        stubIdentities(fixture, false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(redis.observe(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenThrow(new VelocityUnavailableException(
                        VelocityUnavailableException.Cause.DEPENDENCY_FAILURE, "dependency unavailable"));
        VelocityRiskCoordinator coordinator = coordinator(true);

        VelocityAssessment assessment = coordinator.assess(
                fixture.intent(), fixture.attempt(), fixture.method(), null);

        assertThat(assessment.signals()).hasSize(3)
                .allMatch(signal -> signal.availability() == VelocityAvailability.UNAVAILABLE
                        && signal.otherAttemptCount() == null
                        && signal.failureCause() == VelocityUnavailableException.Cause.DEPENDENCY_FAILURE);
        assertThat(assessment.requiresReviewFloor()).isTrue();
        verify(repository, times(3)).save(any());
        verify(redis, never()).compensate(any(), any(), any());
    }

    @Test
    void rollbackCompensatesOnlyTheExactSuccessfulMembers() {
        Fixture fixture = fixture();
        stubIdentities(fixture, false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(redis.observe(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new VelocityObservation(VelocityScriptContract.VERSION, 1_720_000_000_123L, 0L,
                        VelocityObservation.ExecutionMode.FIRST));
        when(redis.compensate(any(), any(), any())).thenReturn(VelocityCompensationResult.REMOVED);
        VelocityRiskCoordinator coordinator = coordinator(true);
        TransactionSynchronizationManager.initSynchronization();

        VelocityAssessment assessment = coordinator.assess(
                fixture.intent(), fixture.attempt(), fixture.method(), null);
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(assessment.signals()).hasSize(3).allMatch(signal -> signal.otherAttemptCount() == 0L);
        verify(redis, times(3)).compensate(any(), any(), any());
        verify(metrics, times(3)).recordCompensation(any(), org.mockito.ArgumentMatchers.eq("REMOVED"));
    }

    @Test
    void rollbackCompensationFailureIsBoundedAndRecordedWithoutMaskingTransactionCompletion() {
        Fixture fixture = fixture();
        stubIdentities(fixture, false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(redis.observe(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new VelocityObservation(VelocityScriptContract.VERSION, 1_720_000_000_123L, 0L,
                        VelocityObservation.ExecutionMode.FIRST));
        when(redis.compensate(any(), any(), any())).thenThrow(new VelocityUnavailableException(
                VelocityUnavailableException.Cause.DEPENDENCY_FAILURE, "cleanup dependency unavailable"));
        VelocityRiskCoordinator coordinator = coordinator(true);
        TransactionSynchronizationManager.initSynchronization();

        coordinator.assess(fixture.intent(), fixture.attempt(), fixture.method(), null);
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(redis, times(3)).compensate(any(), any(), any());
        verify(metrics, times(3)).recordCompensation(any(), org.mockito.ArgumentMatchers.eq("FAILED"));
    }

    @Test
    void disabledConfigurationMakesNoRedisOrDatabaseClaim() {
        Fixture fixture = fixture();

        VelocityAssessment assessment = coordinator(false).assess(
                fixture.intent(), fixture.attempt(), fixture.method(), null);

        assertThat(assessment.signals()).isEmpty();
        verify(redis, never()).observe(any(), any(), any(), any(), any(), any(Integer.class));
        verify(repository, never()).save(any());
    }

    private VelocityRiskCoordinator coordinator(boolean enabled) {
        return new VelocityRiskCoordinator(enabled, redis, identityFactory, repository, metrics, clock);
    }

    private void stubIdentities(Fixture fixture, boolean includeIp) {
        Map<VelocityDimension, String> keys = new EnumMap<>(VelocityDimension.class);
        keys.put(VelocityDimension.CARD_ATTEMPTS_10M, key('a'));
        keys.put(VelocityDimension.CUSTOMER_ATTEMPTS_1H, key('b'));
        keys.put(VelocityDimension.MERCHANT_ATTEMPTS_1M, key('c'));
        if (includeIp) {
            keys.put(VelocityDimension.IP_ATTEMPTS_10M, key('d'));
        }
        when(identityFactory.create(fixture.intent(), fixture.attempt(), fixture.method(), null))
                .thenReturn(new VelocityIdentityFactory.VelocityIdentities(
                        java.util.List.of(new VelocityIdentityFactory.VersionedVelocityIdentities(
                                "test-v1", String.valueOf('e').repeat(64), Map.copyOf(keys))),
                        "test-v1"));
    }

    private Fixture fixture() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(merchantId, customerId, 1200L, "USD", CaptureMethod.AUTOMATIC);
        PaymentMethod method = new PaymentMethod(merchantId, customerId, "token", "fingerprint", "424242",
                "4242", CardFunding.CREDIT, "US");
        PaymentAttempt attempt = new PaymentAttempt(merchantId, intent.getId(), method.getId(), "idem", 1);
        return new Fixture(intent, attempt, method);
    }

    private String key(char value) {
        return "thaap:velocity:v1:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(PaymentIntent intent, PaymentAttempt attempt, PaymentMethod method) {
    }
}
