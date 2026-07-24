package com.thaap.payments.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimitService {
    private final StringRedisTemplate redisTemplate;
    private final RateLimitScriptContract contract;
    private final DefaultRedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public RedisRateLimitService(StringRedisTemplate redisTemplate, RateLimitScriptContract contract) {
        this.redisTemplate = redisTemplate;
        this.contract = contract;
        this.script = new DefaultRedisScript<>(contract.scriptText(), List.class);
    }

    public RateLimitDecision evaluate(String redisKey, String member, Duration window, int limit) {
        if (redisKey == null || !redisKey.matches("thaap:rate-limit:v1:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Rate-limit Redis key has invalid grammar");
        }
        if (member == null || !member.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Rate-limit member has invalid grammar");
        }
        long windowMs = boundedWindow(window);
        if (limit < 1 || limit > 100_000) {
            throw new IllegalArgumentException("Rate-limit maximum must be between 1 and 100000");
        }
        try {
            Object result = redisTemplate.execute(script, List.of(redisKey), RateLimitScriptContract.VERSION,
                    member, Long.toString(windowMs), Integer.toString(limit));
            return contract.parse(result);
        } catch (RateLimitUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(RateLimitUnavailableException.Cause.DEPENDENCY_FAILURE,
                    "Rate-limit dependency operation failed");
        }
    }

    private long boundedWindow(Duration window) {
        if (window == null) {
            throw new IllegalArgumentException("Rate-limit window is required");
        }
        long millis;
        try {
            millis = window.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Rate-limit window is outside the supported range", exception);
        }
        if (millis < 1_000L || millis > 86_400_000L) {
            throw new IllegalArgumentException("Rate-limit window is outside the supported range");
        }
        return millis;
    }
}
