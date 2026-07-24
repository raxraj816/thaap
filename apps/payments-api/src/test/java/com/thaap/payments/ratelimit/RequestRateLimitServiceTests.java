package com.thaap.payments.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestRateLimitServiceTests {

    @Test
    void currentAndPreviousHmacVersionsBothEnforceWithoutRawRedisMaterial() {
        RedisRateLimitService redis = mock(RedisRateLimitService.class);
        RateLimitMetrics metrics = mock(RateLimitMetrics.class);
        when(redis.evaluate(any(), any(), any(), anyInt())).thenReturn(
                new RateLimitDecision(RateLimitScriptContract.VERSION, true, 1_000, 1, 10,
                        1_000, RateLimitDecision.ExecutionMode.FIRST, 0));
        String previous = "v1=" + Base64.getEncoder().encodeToString("previous-pepper".getBytes());
        SensitiveValueHasher hasher = new SensitiveValueHasher("current-pepper", "v2", previous);
        RequestRateLimitService service = service(Duration.ofMinutes(1), redis, hasher, metrics);
        MerchantContext context = new MerchantContext(UUID.randomUUID(), "merchant", "pk_secret_prefix");

        service.enforce(RateLimitOperation.PAYMENT_INTENT_CONFIRM, context,
                "raw-idempotency-key", "203.0.113.17");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> members = ArgumentCaptor.forClass(String.class);
        verify(redis, times(6)).evaluate(keys.capture(), members.capture(), any(), anyInt());
        verify(metrics, times(3)).record(any(), any(), any());
        assertThat(keys.getAllValues()).allMatch(value -> value.matches("thaap:rate-limit:v1:[0-9a-f]{64}"))
                .doesNotHaveDuplicates();
        assertThat(members.getAllValues()).allMatch(value -> value.matches("[0-9a-f]{64}"));
        assertThat(keys.getAllValues().toString() + members.getAllValues()).doesNotContain(
                context.merchantId().toString(), context.apiKeyPrefix(), "203.0.113.17", "raw-idempotency-key");
    }

    @Test
    void invalidWindowFailsAtConstructionBeforeTraffic() {
        RedisRateLimitService redis = mock(RedisRateLimitService.class);
        SensitiveValueHasher hasher = new SensitiveValueHasher("pepper", "v1", "");
        RateLimitMetrics metrics = mock(RateLimitMetrics.class);
        assertThatThrownBy(() -> service(Duration.ofMillis(999), redis, hasher, metrics))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> service(Duration.ofDays(2), redis, hasher, metrics))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    private RequestRateLimitService service(Duration window, RedisRateLimitService redis,
            SensitiveValueHasher hasher, RateLimitMetrics metrics) {
        return new RequestRateLimitService(true, window, 10, 10, 10, 10, 10, 10,
                redis, hasher, metrics);
    }
}
