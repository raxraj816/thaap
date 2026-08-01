package com.thaap.payments.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PaymentRiskProjectionMetrics {

    private final Map<ProjectionProcessingOutcome, Counter> outcomes;
    private final Map<ProjectionConsumerFailure, Counter> failures;
    private final Map<ConsumerQuarantineReason, Counter> quarantineReasons;
    private final Timer processingLatency;

    public PaymentRiskProjectionMetrics(MeterRegistry meterRegistry) {
        EnumMap<ProjectionProcessingOutcome, Counter> counters =
                new EnumMap<>(ProjectionProcessingOutcome.class);
        for (ProjectionProcessingOutcome outcome : ProjectionProcessingOutcome.values()) {
            counters.put(outcome, Counter.builder("thaap.projection.events")
                    .tag("projection", ProjectionNames.PAYMENT_RISK)
                    .tag("outcome", outcome.name())
                    .register(meterRegistry));
        }
        this.outcomes = Map.copyOf(counters);
        EnumMap<ProjectionConsumerFailure, Counter> failureCounters =
                new EnumMap<>(ProjectionConsumerFailure.class);
        for (ProjectionConsumerFailure failure : ProjectionConsumerFailure.values()) {
            failureCounters.put(failure, Counter.builder("thaap.projection.failures")
                    .tag("projection", ProjectionNames.PAYMENT_RISK)
                    .tag("failure", failure.name())
                    .register(meterRegistry));
        }
        this.failures = Map.copyOf(failureCounters);
        EnumMap<ConsumerQuarantineReason, Counter> reasonCounters =
                new EnumMap<>(ConsumerQuarantineReason.class);
        for (ConsumerQuarantineReason reason : ConsumerQuarantineReason.values()) {
            reasonCounters.put(reason, Counter.builder("thaap.projection.quarantine")
                    .tag("projection", ProjectionNames.PAYMENT_RISK)
                    .tag("reason", reason.name())
                    .register(meterRegistry));
        }
        this.quarantineReasons = Map.copyOf(reasonCounters);
        this.processingLatency = Timer.builder("thaap.projection.processing.duration")
                .tag("projection", ProjectionNames.PAYMENT_RISK)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void record(ProjectionProcessingOutcome outcome, long elapsedNanoseconds) {
        outcomes.get(outcome).increment();
        processingLatency.record(Duration.ofNanos(Math.max(0L, elapsedNanoseconds)));
    }

    public void recordFailure(ProjectionConsumerFailure failure, long elapsedNanoseconds) {
        failures.get(failure).increment();
        processingLatency.record(Duration.ofNanos(Math.max(0L, elapsedNanoseconds)));
    }

    public void recordQuarantine(ConsumerQuarantineReason reason) {
        quarantineReasons.get(reason).increment();
    }
}
