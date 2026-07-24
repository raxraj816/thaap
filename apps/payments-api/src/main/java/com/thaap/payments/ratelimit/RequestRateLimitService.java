package com.thaap.payments.ratelimit;

import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RequestRateLimitService {
    private static final String KEY_PREFIX = "thaap:rate-limit:v1:";

    private final boolean enabled;
    private final Duration window;
    private final RedisRateLimitService redisService;
    private final SensitiveValueHasher hasher;
    private final RateLimitMetrics metrics;
    private final Map<RateLimitOperation, Map<RateLimitDimension, Integer>> limits;

    public RequestRateLimitService(
            @Value("${thaap.rate-limit.enabled:true}") boolean enabled,
            @Value("${thaap.rate-limit.window:1m}") Duration window,
            @Value("${thaap.rate-limit.confirm.merchant:100}") int confirmMerchant,
            @Value("${thaap.rate-limit.confirm.credential:60}") int confirmCredential,
            @Value("${thaap.rate-limit.confirm.client-ip:120}") int confirmIp,
            @Value("${thaap.rate-limit.payment-method.merchant:30}") int methodMerchant,
            @Value("${thaap.rate-limit.payment-method.credential:20}") int methodCredential,
            @Value("${thaap.rate-limit.payment-method.client-ip:40}") int methodIp,
            RedisRateLimitService redisService, SensitiveValueHasher hasher, RateLimitMetrics metrics) {
        this.enabled = enabled;
        this.window = validateWindow(window);
        this.redisService = redisService;
        this.hasher = hasher;
        this.metrics = metrics;
        this.limits = Map.of(
                RateLimitOperation.PAYMENT_INTENT_CONFIRM,
                dimensionLimits(confirmMerchant, confirmCredential, confirmIp),
                RateLimitOperation.PAYMENT_METHOD_CREATE,
                dimensionLimits(methodMerchant, methodCredential, methodIp));
    }

    public void enforce(RateLimitOperation operation, MerchantContext context,
            String idempotencyKey, String clientIp) {
        if (!enabled) {
            return;
        }
        String memberMaterial = context.merchantId() + "\0" + operation + "\0" + idempotencyKey;
        Map<String, String> membersByVersion = byVersion(
                hasher.hmacSha256Keyring("rate-limit:request:v1", memberMaterial));
        Map<RateLimitDimension, String> identities = new EnumMap<>(RateLimitDimension.class);
        identities.put(RateLimitDimension.MERCHANT, context.merchantId().toString());
        identities.put(RateLimitDimension.CREDENTIAL, context.apiKeyPrefix());
        identities.put(RateLimitDimension.CLIENT_IP, clientIp == null ? "unknown" : clientIp);
        for (Map.Entry<RateLimitDimension, String> identity : identities.entrySet()) {
            RateLimitDimension dimension = identity.getKey();
            String material = context.merchantId() + "\0" + identity.getValue();
            List<SensitiveValueHasher.VersionedDigest> keyring = hasher.hmacSha256Keyring(
                    "rate-limit:key:" + operation.name().toLowerCase()
                            + ":" + dimension.name().toLowerCase() + ":v1",
                    material);
            boolean everyVersionWasRetry = true;
            for (SensitiveValueHasher.VersionedDigest keyDigest : keyring) {
                String member = membersByVersion.get(keyDigest.keyVersion());
                if (member == null) {
                    throw new IllegalStateException("Rate-limit HMAC keyring versions are inconsistent");
                }
                RateLimitDecision decision;
                try {
                    decision = redisService.evaluate(KEY_PREFIX + keyDigest.digest(), member,
                            window, limits.get(operation).get(dimension));
                } catch (RateLimitUnavailableException exception) {
                    metrics.record(operation, dimension, "UNAVAILABLE_" + exception.getUnavailableCause().name());
                    throw exception;
                }
                if (!decision.allowed()) {
                    metrics.record(operation, dimension, "EXCEEDED");
                    throw new RateLimitExceededException(decision.retryAfterMs());
                }
                everyVersionWasRetry = everyVersionWasRetry
                        && decision.executionMode() == RateLimitDecision.ExecutionMode.RETRY;
            }
            metrics.record(operation, dimension,
                    everyVersionWasRetry ? "ALLOWED_RETRY" : "ALLOWED_FIRST");
        }
    }

    private Map<String, String> byVersion(List<SensitiveValueHasher.VersionedDigest> digests) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SensitiveValueHasher.VersionedDigest digest : digests) {
            result.put(digest.keyVersion(), digest.digest());
        }
        return Map.copyOf(result);
    }

    private Duration validateWindow(Duration value) {
        if (value == null) {
            throw new IllegalArgumentException("Rate-limit window is required");
        }
        long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Rate-limit window is outside the supported range", exception);
        }
        if (millis < 1_000L || millis > 86_400_000L) {
            throw new IllegalArgumentException("Rate-limit window is outside the supported range");
        }
        return value;
    }

    private Map<RateLimitDimension, Integer> dimensionLimits(int merchant, int credential, int ip) {
        validateLimit(merchant);
        validateLimit(credential);
        validateLimit(ip);
        return Map.of(RateLimitDimension.MERCHANT, merchant,
                RateLimitDimension.CREDENTIAL, credential,
                RateLimitDimension.CLIENT_IP, ip);
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100_000) {
            throw new IllegalArgumentException("Application-owned rate limits must be between 1 and 100000");
        }
    }
}
