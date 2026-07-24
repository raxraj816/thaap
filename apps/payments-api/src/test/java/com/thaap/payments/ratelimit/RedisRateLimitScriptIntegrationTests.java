package com.thaap.payments.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimitScriptIntegrationTests {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisRateLimitService service;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        service = new RedisRateLimitService(redisTemplate, new RateLimitScriptContract());
    }

    @Test
    void belowAtAboveRetryTtlAndMemoryBoundariesAreExact() {
        String key = key('a');
        RateLimitDecision first = service.evaluate(key, digest('b'), Duration.ofSeconds(10), 2);
        RateLimitDecision replay = service.evaluate(key, digest('b'), Duration.ofSeconds(10), 2);
        RateLimitDecision at = service.evaluate(key, digest('c'), Duration.ofSeconds(10), 2);
        RateLimitDecision above = service.evaluate(key, digest('d'), Duration.ofSeconds(10), 2);
        assertThat(first.count()).isOne();
        assertThat(replay.executionMode()).isEqualTo(RateLimitDecision.ExecutionMode.RETRY);
        assertThat(at.count()).isEqualTo(2);
        assertThat(above.allowed()).isFalse();
        assertThat(redisTemplate.opsForHash().size(key)).isEqualTo(3L);
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 20L);
    }

    @Test
    void concurrentDistinctMembersHaveOneAllowedAndOneExceededAtBoundary() throws Exception {
        String key = key('e');
        service.evaluate(key, digest('f'), Duration.ofSeconds(10), 2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RateLimitDecision> first = executor.submit(() -> {
                start.await();
                return service.evaluate(key, digest('1'), Duration.ofSeconds(10), 2);
            });
            Future<RateLimitDecision> second = executor.submit(() -> {
                start.await();
                return service.evaluate(key, digest('2'), Duration.ofSeconds(10), 2);
            });
            start.countDown();
            assertThat(List.of(first.get().allowed(), second.get().allowed()).stream()
                    .sorted(Comparator.naturalOrder()).toList()).containsExactly(false, true);
            assertThat(redisTemplate.opsForHash().size(key)).isEqualTo(3L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scriptFlushReloadsFrozenBytesAndRawIdentitiesFailBeforeRedis() {
        service.evaluate(key('3'), digest('4'), Duration.ofSeconds(10), 2);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });
        assertThat(service.evaluate(key('3'), digest('5'), Duration.ofSeconds(10), 2).allowed()).isTrue();
        assertThatThrownBy(() -> service.evaluate("thaap:rate-limit:v1:203.0.113.1", digest('6'),
                Duration.ofSeconds(10), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.evaluate(key('7'), "raw-idempotency-key",
                Duration.ofSeconds(10), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    private String key(char value) { return "thaap:rate-limit:v1:" + digest(value); }
    private String digest(char value) { return String.valueOf(value).repeat(64); }
}
