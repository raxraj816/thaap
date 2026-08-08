package com.thaap.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.authentication.AuthenticationReplayCipher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest
class IdempotencyServiceTests {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private SensitiveValueHasher sensitiveValueHasher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @AfterEach
    void cleanAfterTest() {
        databaseCleaner.clean();
    }

    @Test
    void completedReservationReplaysOriginalResponse() {
        var merchant = createMerchant("merchant-a");
        var context = context(merchant);
        var request = new TestRequest("same-request");

        var first = execute(context, "replay-key", "test.create", request, "first");
        var replay = execute(context, "replay-key", "test.create", request, "ignored");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(idempotencyRepository.findByMerchantIdAndKey(merchant.getId(), "replay-key"))
                .get()
                .extracting(IdempotencyRecord::getStatus)
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void completedReservationReplaysAcrossHashPepperRotation() {
        String oldPepper = "general-idempotency-old-pepper";
        String newPepper = "general-idempotency-new-pepper";
        SensitiveValueHasher oldHasher = new SensitiveValueHasher(
                oldPepper, "general-v1", "");
        IdempotencyService oldService = idempotencyService(oldHasher);
        Merchant merchant = createMerchant("rotation-safe-idempotency");
        MerchantContext context = context(merchant);
        TestRequest request = new TestRequest("rotation-safe-request");
        AtomicInteger supplierCalls = new AtomicInteger();

        ResponseEntity<TestResponse> original = oldService.execute(
                context, "rotation-safe-key", "test.create", request,
                TestResponse.class, () -> {
                    supplierCalls.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(new TestResponse("original"));
                });

        String previousKeyring = "general-v1="
                + Base64.getEncoder().encodeToString(
                        oldPepper.getBytes(StandardCharsets.UTF_8));
        SensitiveValueHasher rotatedHasher = new SensitiveValueHasher(
                newPepper, "general-v2", previousKeyring);
        IdempotencyService rotatedService = idempotencyService(rotatedHasher);
        ResponseEntity<TestResponse> replay = rotatedService.execute(
                context, "rotation-safe-key", "test.create", request,
                TestResponse.class, () -> {
                    supplierCalls.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(new TestResponse("duplicate"));
                });

        assertThat(replay).isEqualTo(original);
        assertThat(supplierCalls).hasValue(1);
        assertThat(idempotencyRepository.findByMerchantIdAndKey(
                        merchant.getId(), "rotation-safe-key"))
                .get()
                .extracting(IdempotencyRecord::getRequestHashKeyVersion)
                .isEqualTo("general-v1");

        IdempotencyKeyConfiguration missingOldKey =
                new IdempotencyKeyConfiguration(
                        idempotencyRepository,
                        new SensitiveValueHasher(newPepper, "general-v2", ""),
                        DatabaseInstant.tick(Clock.systemUTC()));
        assertThatThrownBy(missingOldKey::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("general-v1");
    }

    @Test
    void expiredReplayCanRestartAfterItsHistoricalPepperIsRemoved()
            throws Exception {
        String oldPepper = "expired-idempotency-old-pepper";
        SensitiveValueHasher oldHasher = new SensitiveValueHasher(
                oldPepper, "expired-v1", "");
        Merchant merchant = createMerchant("expired-key-retirement");
        MerchantContext context = context(merchant);
        TestRequest oldRequest = new TestRequest("old-request");
        String serializedOldRequest = objectMapper.writeValueAsString(oldRequest);
        Instant oldStartedAt = DatabaseInstant.now().minus(2, ChronoUnit.DAYS);
        UUID oldLeaseToken = UUID.randomUUID();
        IdempotencyRecord expired = new IdempotencyRecord(
                IdempotencyPrincipal.merchant(context), "reusable-expired-key", "test.old",
                oldHasher.hmacSha256(
                        "idempotency.request", serializedOldRequest, "expired-v1"),
                "expired-v1", oldLeaseToken, oldStartedAt.plusSeconds(30),
                oldStartedAt, oldStartedAt.plus(1, ChronoUnit.DAYS));
        expired.complete(
                oldLeaseToken, HttpStatus.CREATED.value(),
                objectMapper.writeValueAsString(new TestResponse("old")),
                oldStartedAt.plusSeconds(1),
                DatabaseInstant.now().minusSeconds(1));
        idempotencyRepository.saveAndFlush(expired);

        SensitiveValueHasher newHasher = new SensitiveValueHasher(
                "expired-idempotency-new-pepper", "expired-v2", "");
        IdempotencyKeyConfiguration retiredOldKey =
                new IdempotencyKeyConfiguration(
                        idempotencyRepository, newHasher, DatabaseInstant.tick(Clock.systemUTC()));
        retiredOldKey.afterPropertiesSet();

        AtomicInteger supplierCalls = new AtomicInteger();
        ResponseEntity<TestResponse> restarted = idempotencyService(newHasher).execute(
                context, "reusable-expired-key", "test.new",
                new TestRequest("new-request"), TestResponse.class, () -> {
                    supplierCalls.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(new TestResponse("new"));
                });

        assertThat(restarted.getBody()).isEqualTo(new TestResponse("new"));
        assertThat(supplierCalls).hasValue(1);
        assertThat(idempotencyRepository.findById(expired.getId()))
                .get()
                .satisfies(record -> {
                    assertThat(record.getOperation()).isEqualTo("test.new");
                    assertThat(record.getRequestHashKeyVersion())
                            .isEqualTo("expired-v2");
                    assertThat(record.getExpiresAt()).isAfter(DatabaseInstant.now());
                });
    }

    @Test
    void sameKeyWithDifferentOperationConflictsEvenWhenBodyMatches() {
        var merchant = createMerchant("merchant-a");
        var context = context(merchant);
        var request = new TestRequest("same-body");
        execute(context, "operation-key", "payment_intents.capture", request, "captured");

        assertThatThrownBy(() -> execute(context, "operation-key", "payment_intents.cancel", request, "canceled"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void inProgressReservationSerializesDuplicateAndReplaysWithoutSecondMutation() throws Exception {
        var merchant = createMerchant("merchant-a");
        var context = context(merchant);
        var request = new TestRequest("slow-request");
        var operationStarted = new CountDownLatch(1);
        var finishOperation = new CountDownLatch(1);
        var duplicateSupplierCalls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> idempotencyService.execute(context, "slow-key", "test.slow", request,
                    TestResponse.class, () -> {
                        operationStarted.countDown();
                        await(finishOperation);
                        return ResponseEntity.status(HttpStatus.CREATED).body(new TestResponse("first"));
                    }));

            assertThat(operationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var duplicate = executor.submit(() ->
                    idempotencyService.execute(
                            context, "slow-key", "test.slow", request,
                            TestResponse.class, () -> {
                        duplicateSupplierCalls.incrementAndGet();
                        return ResponseEntity.status(HttpStatus.CREATED).body(new TestResponse("duplicate"));
                    }));

            assertThatThrownBy(() -> duplicate.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(duplicateSupplierCalls).hasValue(0);

            finishOperation.countDown();
            ResponseEntity<TestResponse> firstResponse = first.get(5, TimeUnit.SECONDS);
            ResponseEntity<TestResponse> replayed = duplicate.get(5, TimeUnit.SECONDS);
            assertThat(firstResponse.getBody()).isEqualTo(new TestResponse("first"));
            assertThat(replayed.getBody()).isEqualTo(firstResponse.getBody());
            assertThat(duplicateSupplierCalls).hasValue(0);
        } finally {
            finishOperation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedMutationReleasesReservationSoRetryCanSucceed() {
        var merchant = createMerchant("merchant-a");
        var context = context(merchant);
        var request = new TestRequest("retryable-failure");

        assertThatThrownBy(() -> idempotencyService.execute(context, "retry-key", "test.create", request,
                TestResponse.class, () -> {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "simulated downstream failure");
                }))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(idempotencyRepository.findByMerchantIdAndKey(merchant.getId(), "retry-key")).isEmpty();

        var retry = execute(context, "retry-key", "test.create", request, "recovered");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(new TestResponse("recovered"));
        assertThat(idempotencyRepository.findByMerchantIdAndKey(merchant.getId(), "retry-key"))
                .get()
                .extracting(IdempotencyRecord::getStatus)
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void sameKeyCanBeUsedByDifferentMerchantsWithoutReplayOrConflict() {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var request = new TestRequest("shared-key");

        var firstA = execute(context(merchantA), "merchant-scoped-key", "test.create", request, "merchant-a");
        var firstB = execute(context(merchantB), "merchant-scoped-key", "test.create", request, "merchant-b");

        assertThat(firstA.getBody()).isEqualTo(new TestResponse("merchant-a"));
        assertThat(firstB.getBody()).isEqualTo(new TestResponse("merchant-b"));
        assertThat(idempotencyRepository.findByMerchantIdAndKey(merchantA.getId(), "merchant-scoped-key")).isPresent();
        assertThat(idempotencyRepository.findByMerchantIdAndKey(merchantB.getId(), "merchant-scoped-key")).isPresent();
    }

    @Test
    void inProgressRecordStoresOperationRequestHashAndExpirationWithoutResponsePayload() throws Exception {
        var merchant = createMerchant("merchant-a");
        var request = new TestRequest("manual-in-progress");
        var requestHash = requestHash(request);

        Instant now = DatabaseInstant.now();
        idempotencyRepository.saveAndFlush(new IdempotencyRecord(
                merchant.getId(), "manual-key", "test.create", requestHash,
                sensitiveValueHasher.currentKeyVersion(), UUID.randomUUID(),
                now.plusSeconds(30), now, now.plus(1, ChronoUnit.DAYS)));

        var record = idempotencyRepository.findByMerchantIdAndKey(merchant.getId(), "manual-key").orElseThrow();
        assertThat(record.getOperation()).isEqualTo("test.create");
        assertThat(record.getRequestHash()).isEqualTo(requestHash);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.getHttpStatus()).isNull();
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.getExpiresAt()).isAfter(DatabaseInstant.now());
        assertThat(record.getLeaseToken()).isNotNull();
        assertThat(record.getLeaseExpiresAt()).isAfter(DatabaseInstant.now());
    }

    @Test
    void expiredReservationIsFencedReclaimedAndCompleted() throws Exception {
        Merchant merchant = createMerchant("merchant-expired-reservation");
        MerchantContext merchantContext = context(merchant);
        TestRequest request = new TestRequest("expired-request");
        Instant now = DatabaseInstant.now();
        IdempotencyRecord expired = new IdempotencyRecord(
                IdempotencyPrincipal.merchant(merchantContext), "expired-key", "test.create", requestHash(request),
                sensitiveValueHasher.currentKeyVersion(),
                UUID.randomUUID(), now.minusSeconds(1), now.minusSeconds(60),
                now.plus(1, ChronoUnit.DAYS));
        idempotencyRepository.saveAndFlush(expired);

        ResponseEntity<TestResponse> response = execute(
                merchantContext, "expired-key", "test.create", request, "reclaimed");

        assertThat(response.getBody()).isEqualTo(new TestResponse("reclaimed"));
        IdempotencyRecord completed = idempotencyRepository.findById(expired.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(completed.getLeaseToken()).isNull();
        assertThat(completed.getLeaseExpiresAt()).isNull();
    }

    private ResponseEntity<TestResponse> execute(MerchantContext context, String key, String operation,
            TestRequest request, String value) {
        return idempotencyService.execute(context, key, operation, request, TestResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(new TestResponse(value)));
    }

    private IdempotencyService idempotencyService(SensitiveValueHasher hasher) {
        return new IdempotencyService(
                idempotencyRepository, objectMapper, hasher,
                new AuthenticationReplayCipher(
                        "dGVzdC1hdXRoLXJlcGxheS1rZXktMzItYnl0ZXMtMTI=", "test-v1", ""),
                transactionManager,
                DatabaseInstant.tick(Clock.systemUTC()), Duration.ofSeconds(30));
    }

    private Merchant createMerchant(String name) {
        return merchantRepository.saveAndFlush(new Merchant(name, "thaap_test_" + UUID.randomUUID()));
    }

    private MerchantContext context(Merchant merchant) {
        return new MerchantContext(merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix());
    }

    private String requestHash(TestRequest request) throws JsonProcessingException {
        return sensitiveValueHasher.hmacSha256("idempotency.request", objectMapper.writeValueAsString(request));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for idempotency test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for idempotency test latch", exception);
        }
    }

    private record TestRequest(String value) {
    }

    private record TestResponse(String value) {
    }
}
