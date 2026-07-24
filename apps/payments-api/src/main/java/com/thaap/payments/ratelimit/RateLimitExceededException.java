package com.thaap.payments.ratelimit;

public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterMs) {
        super("Application-owned request rate limit exceeded");
        this.retryAfterSeconds = Math.max(1L, Math.min(86_400L, (retryAfterMs + 999L) / 1_000L));
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
