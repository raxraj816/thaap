package com.thaap.payments.velocity;

import java.time.Instant;
import java.util.Objects;

public record VelocitySignal(VelocityDimension dimension, VelocityAvailability availability,
        Long otherAttemptCount, Instant observedAt, String scriptVersion,
        VelocityObservation.ExecutionMode executionMode, VelocityUnavailableException.Cause failureCause) {

    public VelocitySignal {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(availability, "availability");
        if (availability == VelocityAvailability.AVAILABLE) {
            Objects.requireNonNull(otherAttemptCount, "otherAttemptCount");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(scriptVersion, "scriptVersion");
            Objects.requireNonNull(executionMode, "executionMode");
            if (otherAttemptCount < 0 || failureCause != null) {
                throw new IllegalArgumentException("Available velocity evidence is internally inconsistent");
            }
        } else if (otherAttemptCount != null || observedAt != null || executionMode != null || failureCause == null) {
            throw new IllegalArgumentException("Unavailable velocity evidence is internally inconsistent");
        }
    }

    public static VelocitySignal available(VelocityDimension dimension, VelocityObservation observation) {
        return new VelocitySignal(dimension, VelocityAvailability.AVAILABLE, observation.otherAttemptCount(),
                Instant.ofEpochMilli(observation.redisTimeMs()), observation.scriptVersion(),
                observation.executionMode(), null);
    }

    public static VelocitySignal unavailable(VelocityDimension dimension,
            VelocityUnavailableException.Cause failureCause) {
        return new VelocitySignal(dimension, VelocityAvailability.UNAVAILABLE, null, null,
                VelocityScriptContract.VERSION, null, failureCause);
    }

    public boolean thresholdMatched() {
        return availability == VelocityAvailability.AVAILABLE
                && otherAttemptCount >= dimension.reviewThreshold();
    }
}
