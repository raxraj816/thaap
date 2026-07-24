package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.security.SensitiveValueHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisVelocityScriptIntegrationTests {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisVelocityService service;
    private VelocityScriptContract contract;
    private VelocityCompensationScriptContract compensationContract;

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
        contract = new VelocityScriptContract();
        compensationContract = new VelocityCompensationScriptContract();
        service = new RedisVelocityService(redisTemplate, contract, compensationContract);
    }

    @Test
    void firstRetryDistinctAndCompensationHaveExactCurrentAttemptExclusionAndTtl() {
        String key = key('a');
        String firstMember = digest('b');
        String secondMember = digest('c');

        VelocityObservation first = service.observe(key, firstMember, token('1'),
                Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
        VelocityObservation retry = service.observe(key, firstMember, token('2'),
                Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
        VelocityObservation second = service.observe(key, secondMember, token('3'),
                Duration.ofSeconds(10), Duration.ofSeconds(12), 100);

        assertThat(first.otherAttemptCount()).isZero();
        assertThat(first.executionMode()).isEqualTo(VelocityObservation.ExecutionMode.FIRST);
        assertThat(retry.otherAttemptCount()).isZero();
        assertThat(retry.executionMode()).isEqualTo(VelocityObservation.ExecutionMode.RETRY);
        assertThat(second.otherAttemptCount()).isOne();
        assertThat(redisTemplate.opsForZSet().size(key)).isEqualTo(2L);
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 12L);

        assertThat(service.compensate(key, secondMember, token('3')))
                .isEqualTo(VelocityCompensationResult.REMOVED);
        assertThat(service.compensate(key, secondMember, token('3')))
                .isEqualTo(VelocityCompensationResult.NOT_FOUND);
        VelocityObservation retriedAfterRollback = service.observe(key, secondMember, token('4'),
                Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
        assertThat(retriedAfterRollback.otherAttemptCount()).isOne();
        assertThat(retriedAfterRollback.executionMode()).isEqualTo(VelocityObservation.ExecutionMode.FIRST);
    }

    @Test
    void rollbackOfRetryCannotDeleteAnExistingLogicalAttempt() {
        String key = key('f');
        String member = digest('a');

        service.observe(key, member, token('1'), Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
        service.observe(key, member, token('2'), Duration.ofSeconds(10), Duration.ofSeconds(12), 100);

        assertThat(service.compensate(key, member, token('2')))
                .isEqualTo(VelocityCompensationResult.STALE);
        assertThat(redisTemplate.opsForZSet().score(key, member)).isNotNull();
        assertThat(service.compensate(key, member, token('1')))
                .isEqualTo(VelocityCompensationResult.REMOVED);
        assertThat(redisTemplate.opsForZSet().score(key, member)).isNull();
    }

    @Test
    void staleRollbackCompensationCannotDeleteANewerInsertedReservation() {
        String key = key('e');
        String member = digest('d');

        service.observe(key, member, token('1'), Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
        assertThat(service.compensate(key, member, token('1')))
                .isEqualTo(VelocityCompensationResult.REMOVED);
        service.observe(key, member, token('2'), Duration.ofSeconds(10), Duration.ofSeconds(12), 100);

        assertThat(service.compensate(key, member, token('1')))
                .isEqualTo(VelocityCompensationResult.STALE);
        assertThat(redisTemplate.opsForZSet().score(key, member)).isNotNull();
        assertThat(service.compensate(key, member, token('2')))
                .isEqualTo(VelocityCompensationResult.REMOVED);
    }

    @Test
    void rollingKeyringKeepsPreviousWindowVisibleWithoutDoubleCountingCurrentAttempt() {
        SensitiveValueHasher previousHasher = new SensitiveValueHasher(
                "velocity-old-pepper", "velocity-v1", "");
        SensitiveValueHasher rollingHasher = new SensitiveValueHasher(
                "velocity-new-pepper", "velocity-v2",
                "velocity-v1=dmVsb2NpdHktb2xkLXBlcHBlcg==");
        VelocityIdentityFactory previousFactory = new VelocityIdentityFactory(previousHasher);
        VelocityIdentityFactory rollingFactory = new VelocityIdentityFactory(rollingHasher);
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentMethod method = new PaymentMethod(merchantId, customerId, "token", "rotation-card",
                "424242", "4242", CardFunding.CREDIT, "US");
        for (int index = 0; index < 3; index++) {
            PaymentIntent historicalIntent = new PaymentIntent(
                    merchantId, customerId, 1200L, "USD", CaptureMethod.AUTOMATIC);
            PaymentAttempt historicalAttempt = new PaymentAttempt(
                    merchantId, historicalIntent.getId(), method.getId(), "old-" + index, 1);
            VelocityIdentityFactory.VersionedVelocityIdentities previous = previousFactory.create(
                    historicalIntent, historicalAttempt, method, null).versions().getFirst();
            service.observe(previous.redisKeys().get(VelocityDimension.CARD_ATTEMPTS_10M),
                    previous.member(), token((char) ('1' + index)),
                    VelocityDimension.CARD_ATTEMPTS_10M.window(),
                    VelocityDimension.CARD_ATTEMPTS_10M.ttl(),
                    VelocityDimension.CARD_ATTEMPTS_10M.maximumMembers());
        }
        PaymentIntent currentIntent = new PaymentIntent(
                merchantId, customerId, 1200L, "USD", CaptureMethod.AUTOMATIC);
        PaymentAttempt currentAttempt = new PaymentAttempt(
                merchantId, currentIntent.getId(), method.getId(), "current", 1);
        VelocityObservationRecordRepository repository = mock(VelocityObservationRecordRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        VelocityRiskCoordinator coordinator = new VelocityRiskCoordinator(true, service, rollingFactory,
                repository, mock(VelocityMetrics.class),
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC));

        VelocityAssessment assessment = coordinator.assess(currentIntent, currentAttempt, method, null);

        VelocitySignal cardSignal = assessment.signals().stream()
                .filter(signal -> signal.dimension() == VelocityDimension.CARD_ATTEMPTS_10M)
                .findFirst().orElseThrow();
        assertThat(cardSignal.otherAttemptCount()).isEqualTo(3L);
        assertThat(cardSignal.thresholdMatched()).isTrue();
        VelocityIdentityFactory.VelocityIdentities rollingIdentities = rollingFactory.create(
                currentIntent, currentAttempt, method, null);
        String currentKey = rollingIdentities.versions().get(0).redisKeys()
                .get(VelocityDimension.CARD_ATTEMPTS_10M);
        String previousKey = rollingIdentities.versions().get(1).redisKeys()
                .get(VelocityDimension.CARD_ATTEMPTS_10M);
        assertThat(redisTemplate.opsForZSet().size(currentKey)).isOne();
        assertThat(redisTemplate.opsForZSet().size(previousKey)).isEqualTo(4L);
    }

    @Test
    void exactWindowExpiryPrunesAnAbandonedMemberBeforeCounting() {
        String key = key('d');
        redisTemplate.opsForZSet().add(key, digest('e'), 0.0d);
        redisTemplate.expire(key, Duration.ofMillis(1500));

        VelocityObservation afterWindow = service.observe(
                key, digest('f'), token('5'), Duration.ofSeconds(1), Duration.ofMillis(1500), 100);

        assertThat(afterWindow.otherAttemptCount()).isZero();
        assertThat(redisTemplate.opsForZSet().size(key)).isOne();
    }

    @Test
    void concurrentAtomicScriptsProduceOneCoherentZeroAndOneCountWithoutPartialState() throws Exception {
        String key = key('6');
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<VelocityObservation> first = executor.submit(() -> {
                start.await();
                return service.observe(key, digest('7'), token('7'),
                        Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
            });
            Future<VelocityObservation> second = executor.submit(() -> {
                start.await();
                return service.observe(key, digest('8'), token('8'),
                        Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
            });
            start.countDown();

            assertThat(List.of(first.get().otherAttemptCount(), second.get().otherAttemptCount())
                    .stream().sorted(Comparator.naturalOrder()).toList()).containsExactly(0L, 1L);
            assertThat(redisTemplate.opsForZSet().size(key)).isEqualTo(2L);
            assertThat(redisTemplate.getExpire(key)).isBetween(1L, 12L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scriptFlushRecoversByLoadingTheSameFrozenBytes() {
        String key = key('9');
        service.observe(key, digest('a'), token('a'), Duration.ofSeconds(10), Duration.ofSeconds(12), 100);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });

        VelocityObservation observation = service.observe(
                key, digest('b'), token('b'), Duration.ofSeconds(10), Duration.ofSeconds(12), 100);

        assertThat(observation.otherAttemptCount()).isOne();
        List<Boolean> loaded = redisTemplate.execute((RedisCallback<List<Boolean>>) connection ->
                connection.scriptingCommands().scriptExists(VelocityScriptContract.SHA1));
        assertThat(loaded).containsExactly(true);
    }

    @Test
    void readinessLoadsAndVerifiesTheFrozenScriptOnRealRedis() {
        VelocityRedisHealthIndicator indicator = new VelocityRedisHealthIndicator(
                true, redisTemplate, contract, compensationContract);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        List<Boolean> loaded = redisTemplate.execute((RedisCallback<List<Boolean>>) connection ->
                connection.scriptingCommands().scriptExists(VelocityScriptContract.SHA1));
        assertThat(loaded).containsExactly(true);
        List<Boolean> compensationLoaded = redisTemplate.execute((RedisCallback<List<Boolean>>) connection ->
                connection.scriptingCommands().scriptExists(VelocityCompensationScriptContract.SHA1));
        assertThat(compensationLoaded).containsExactly(true);
    }

    @Test
    void scriptReturnsClosedWrongVersionAndCardinalityLimitResultsThatParserRejects() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>(contract.scriptText(), List.class);
        Object wrongVersion = redisTemplate.execute(script,
                List.of(key('1'), ownerKey(key('1'), digest('2'))),
                "velocity_v2", digest('2'), "1000", "1500", "1", token('2'));
        assertThatThrownBy(() -> contract.parse(wrongVersion))
                .isInstanceOfSatisfying(VelocityUnavailableException.class,
                        exception -> assertThat(exception.getUnavailableCause())
                                .isEqualTo(VelocityUnavailableException.Cause.WRONG_VERSION));

        service.observe(key('3'), digest('4'), token('4'), Duration.ofSeconds(10), Duration.ofSeconds(12), 1);
        assertThatThrownBy(() -> service.observe(
                key('3'), digest('5'), token('5'), Duration.ofSeconds(10), Duration.ofSeconds(12), 1))
                .isInstanceOfSatisfying(VelocityUnavailableException.class,
                        exception -> assertThat(exception.getUnavailableCause())
                                .isEqualTo(VelocityUnavailableException.Cause.CARDINALITY_LIMIT));
        assertThat(redisTemplate.opsForZSet().size(key('3'))).isOne();
    }

    @Test
    void clientBoundaryRejectsRawOrUnboundedKeysMembersWindowsTtlsAndLimitsBeforeRedis() {
        assertThatThrownBy(() -> service.observe("thaap:velocity:v1:raw-ip-203.0.113.1", digest('a'), token('a'),
                Duration.ofSeconds(1), Duration.ofSeconds(2), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.observe(key('a'), "raw-member", token('a'), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.observe(key('a'), digest('b'), token('b'), Duration.ofMillis(999),
                Duration.ofSeconds(2), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.observe(key('a'), digest('b'), token('b'), Duration.ofSeconds(2),
                Duration.ofSeconds(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.observe(key('a'), digest('b'), token('b'), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 100_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.observe(key('a'), digest('b'), "raw-token", Duration.ofSeconds(2),
                Duration.ofSeconds(3), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(redisTemplate.keys("*")).isEmpty();
    }

    private String key(char value) {
        return "thaap:velocity:v1:" + digest(value);
    }

    private String digest(char value) {
        return String.valueOf(value).repeat(64);
    }

    private String token(char value) {
        return String.valueOf(value).repeat(32);
    }

    private String ownerKey(String redisKey, String member) {
        return "thaap:velocity:v1:owner:" + redisKey.substring(redisKey.lastIndexOf(':') + 1) + ":" + member;
    }
}
