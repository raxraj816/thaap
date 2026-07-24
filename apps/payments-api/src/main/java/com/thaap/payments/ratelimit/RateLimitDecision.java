package com.thaap.payments.ratelimit;

public record RateLimitDecision(String scriptVersion, boolean allowed, long redisTimeMs,
        long count, long limit, long retryAfterMs, ExecutionMode executionMode,
        long windowStartedAtMs) {

    public enum ExecutionMode {
        FIRST,
        RETRY,
        REJECTED
    }
}
