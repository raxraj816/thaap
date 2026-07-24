package com.thaap.payments.velocity;

import java.time.Duration;

public enum VelocityDimension {
    CARD_ATTEMPTS_10M("velocity.card_attempts_10m", Duration.ofMinutes(10), Duration.ofMinutes(20), 10_000, 3),
    IP_ATTEMPTS_10M("velocity.ip_attempts_10m", Duration.ofMinutes(10), Duration.ofMinutes(20), 10_000, 5),
    CUSTOMER_ATTEMPTS_1H("velocity.customer_attempts_1h", Duration.ofHours(1), Duration.ofHours(2), 10_000, 3),
    MERCHANT_ATTEMPTS_1M("velocity.merchant_attempts_1m", Duration.ofMinutes(1), Duration.ofMinutes(2), 100_000, 20);

    private final String featureCode;
    private final Duration window;
    private final Duration ttl;
    private final int maximumMembers;
    private final long reviewThreshold;

    VelocityDimension(String featureCode, Duration window, Duration ttl, int maximumMembers, long reviewThreshold) {
        this.featureCode = featureCode;
        this.window = window;
        this.ttl = ttl;
        this.maximumMembers = maximumMembers;
        this.reviewThreshold = reviewThreshold;
    }

    public String featureCode() { return featureCode; }
    public Duration window() { return window; }
    public Duration ttl() { return ttl; }
    public int maximumMembers() { return maximumMembers; }
    public long reviewThreshold() { return reviewThreshold; }
}
