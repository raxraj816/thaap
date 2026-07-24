package com.thaap.payments.velocity;

import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class VelocityRiskCoordinator {

    private final boolean enabled;
    private final RedisVelocityService redisVelocityService;
    private final VelocityIdentityFactory identityFactory;
    private final VelocityObservationRecordRepository repository;
    private final VelocityMetrics metrics;
    private final Clock clock;

    public VelocityRiskCoordinator(@Value("${thaap.fraud.velocity.enabled:true}") boolean enabled,
            RedisVelocityService redisVelocityService, VelocityIdentityFactory identityFactory,
            VelocityObservationRecordRepository repository, VelocityMetrics metrics, Clock clock) {
        this.enabled = enabled;
        this.redisVelocityService = redisVelocityService;
        this.identityFactory = identityFactory;
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
    }

    public VelocityAssessment assess(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, RiskContext riskContext) {
        if (!enabled) {
            return VelocityAssessment.disabled();
        }
        VelocityIdentityFactory.VelocityIdentities identities = identityFactory.create(
                paymentIntent, paymentAttempt, paymentMethod, riskContext);
        List<VelocitySignal> signals = new ArrayList<>();
        List<VelocityObservationRecord> records = new ArrayList<>();
        List<VelocityReservation> reservations = new ArrayList<>();
        Instant recordedAt = clock.instant();
        for (VelocityDimension dimension : VelocityDimension.values()) {
            List<VelocityIdentityFactory.VersionedVelocityIdentities> dimensionIdentities = identities.versions()
                    .stream().filter(version -> version.redisKeys().containsKey(dimension)).toList();
            if (dimensionIdentities.isEmpty()) {
                continue;
            }
            List<VelocityObservation> observations = new ArrayList<>();
            VelocitySignal signal = null;
            for (VelocityIdentityFactory.VersionedVelocityIdentities version : dimensionIdentities) {
                String redisKey = version.redisKeys().get(dimension);
                String reservationToken = UUID.randomUUID().toString().replace("-", "");
                try {
                    VelocityObservation observation = redisVelocityService.observe(redisKey, version.member(),
                            reservationToken, dimension.window(), dimension.ttl(), dimension.maximumMembers());
                    observations.add(observation);
                    VelocityReservation reservation = new VelocityReservation(
                            redisKey, version.member(), reservationToken);
                    reservations.add(reservation);
                    registerRollbackCompensation(dimension, reservation);
                } catch (VelocityUnavailableException exception) {
                    signal = VelocitySignal.unavailable(dimension, exception.getUnavailableCause());
                    break;
                }
            }
            if (signal == null) {
                VelocityObservation current = observations.getFirst();
                long maximumOtherAttempts = observations.stream()
                        .mapToLong(VelocityObservation::otherAttemptCount).max().orElseThrow();
                long latestRedisTime = observations.stream()
                        .mapToLong(VelocityObservation::redisTimeMs).max().orElseThrow();
                signal = VelocitySignal.available(dimension, new VelocityObservation(
                        current.scriptVersion(), latestRedisTime, maximumOtherAttempts,
                        current.executionMode()));
            }
            signals.add(signal);
            metrics.recordLookupAfterCompletion(signal);
            records.add(repository.save(new VelocityObservationRecord(paymentIntent.getMerchantId(),
                    paymentIntent.getId(), paymentAttempt.getId(), signal, identities.keyVersion(),
                    paymentAttempt.getTrafficOrigin(), recordedAt)));
        }
        return new VelocityAssessment(signals, records, reservations);
    }

    private void registerRollbackCompensation(VelocityDimension dimension, VelocityReservation reservation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    VelocityCompensationResult result = redisVelocityService.compensate(
                            reservation.redisKey(), reservation.member(), reservation.reservationToken());
                    metrics.recordCompensation(dimension, result.name());
                } catch (RuntimeException exception) {
                    metrics.recordCompensation(dimension, "FAILED");
                }
            }
        });
    }
}
