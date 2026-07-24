package com.thaap.payments.velocity;

public record VelocityObservation(String scriptVersion, long redisTimeMs, long otherAttemptCount,
        ExecutionMode executionMode) {

    public enum ExecutionMode {
        FIRST,
        RETRY
    }
}
