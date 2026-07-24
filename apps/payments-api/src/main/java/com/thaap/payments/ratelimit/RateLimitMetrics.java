package com.thaap.payments.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RateLimitMetrics {
    private final MeterRegistry registry;

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(RateLimitOperation operation, RateLimitDimension dimension, String outcome) {
        registry.counter("thaap.rate_limit.decisions", "operation", operation.name(),
                "dimension", dimension.name(), "outcome", outcome).increment();
    }
}
