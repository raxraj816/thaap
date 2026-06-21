package com.thaap.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    void inProgressReservationRejectsDuplicateRequestBeforeMutationRuns() throws Exception {
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

            var duplicate = executor.submit(() -> {
                try {
                    idempotencyService.execute(context, "slow-key", "test.slow", request, TestResponse.class, () -> {
                        duplicateSupplierCalls.incrementAndGet();
                        return ResponseEntity.status(HttpStatus.CREATED).body(new TestResponse("duplicate"));
                    });
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });

            var duplicateFailure = duplicate.get(5, TimeUnit.SECONDS);
            assertThat(duplicateFailure)
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode")
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(((ResponseStatusException) duplicateFailure).getReason())
                    .isEqualTo("Idempotency key is already in progress");
            assertThat(duplicateSupplierCalls).hasValue(0);

            finishOperation.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getBody()).isEqualTo(new TestResponse("first"));
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

        idempotencyRepository.saveAndFlush(new IdempotencyRecord(merchant.getId(), "manual-key", "test.create",
                requestHash, Instant.now().plus(1, ChronoUnit.DAYS)));

        var record = idempotencyRepository.findByMerchantIdAndKey(merchant.getId(), "manual-key").orElseThrow();
        assertThat(record.getOperation()).isEqualTo("test.create");
        assertThat(record.getRequestHash()).isEqualTo(requestHash);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.getHttpStatus()).isNull();
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.getExpiresAt()).isAfter(Instant.now());
    }

    private ResponseEntity<TestResponse> execute(MerchantContext context, String key, String operation,
            TestRequest request, String value) {
        return idempotencyService.execute(context, key, operation, request, TestResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(new TestResponse(value)));
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
