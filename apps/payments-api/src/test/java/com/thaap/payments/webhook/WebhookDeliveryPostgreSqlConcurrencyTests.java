package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperatorService;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WebhookDeliveryPostgreSqlConcurrencyTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private WebhookEndpointService endpointService;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookEndpointCreationRecordRepository creationRecordRepository;

    @Autowired
    private WebhookEndpointCreationIdentityRepository creationIdentityRepository;

    @Autowired
    private SensitiveValueHasher sensitiveValueHasher;

    @Autowired
    private WebhookEndpointSubscriptionStore subscriptionStore;

    @Autowired
    private WebhookSecretCipher secretCipher;

    @MockitoSpyBean
    private WebhookEndpointSecretService endpointSecretService;

    @Autowired
    private WebhookSigner webhookSigner;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookMetrics webhookMetrics;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Autowired
    private WebhookDeliveryClaimService claimService;

    @Autowired
    private WebhookDeliveryFinalizationService finalizationService;

    @Autowired
    private WebhookOperationsService operationsService;

    @Autowired
    private RiskOperatorService riskOperatorService;

    @MockitoSpyBean
    private WebhookEndpointVerificationService verificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WebhookHttpTransport transport;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
        reset(transport);
    }

    @Test
    void expiredCreationReservationReclaimSerializesAndReplaysOneEndpoint() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("creation-reclaim-race", "thaap_creation_reclaim_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "creation-reclaim-race", merchant.getApiKeyPrefix());
        String idempotencyKey = "creation-reclaim-key";
        String url = "https://merchant.example/reclaim-race";
        Instant staleStartedAt = Instant.now().minusSeconds(120);
        WebhookEndpointCreationRecord staleRecord = new WebhookEndpointCreationRecord(
                merchant.getId(),
                sensitiveValueHasher.hmacSha256(
                        "webhook.endpoint.idempotency", idempotencyKey),
                sensitiveValueHasher.hmacSha256(
                        "webhook.endpoint.create", url + "\nrefund.created"),
                sensitiveValueHasher.currentKeyVersion(),
                UUID.randomUUID(), staleStartedAt.plusSeconds(30),
                staleStartedAt, staleStartedAt.plusSeconds(24 * 60 * 60));
        creationRecordRepository.saveAndFlush(staleRecord);
        CountDownLatch firstOwnsCompletionLock = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        AtomicReference<WebhookEndpointService.EndpointSecretResult> firstResult =
                new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    firstResult.set(endpointService.create(
                            context, idempotencyKey, url,
                            Set.of("refund.created"), "reclaim-first"));
                    firstOwnsCompletionLock.countDown();
                    await(releaseFirstCommit);
                });
                return null;
            });
            firstOwnsCompletionLock.await();
            awaitCreationRecordLockConflict(staleRecord.getId());

            Future<WebhookEndpointService.EndpointSecretResult> second = executor.submit(() ->
                    endpointService.create(
                            context, idempotencyKey, url,
                            Set.of("refund.created"), "reclaim-second"));
            awaitCreationRecordLockWait();

            releaseFirstCommit.countDown();
            first.get();
            WebhookEndpointService.EndpointSecretResult replayed = second.get();

            assertThat(replayed.endpoint().getId())
                    .isEqualTo(firstResult.get().endpoint().getId());
            assertThat(replayed.rawSigningSecret())
                    .isEqualTo(firstResult.get().rawSigningSecret());
            assertThat(endpointRepository.count()).isEqualTo(1);
            WebhookEndpointCreationRecord completed =
                    creationRecordRepository.findById(staleRecord.getId()).orElseThrow();
            assertThat(completed.getStatus()).isEqualTo(WebhookEndpointCreationStatus.COMPLETED);
            assertThat(completed.getLeaseToken()).isNull();
        } finally {
            releaseFirstCommit.countDown();
        }
    }

    @Test
    void liveEndpointCreationLockSurvivesLeaseExpiryBeforeFirstMutationCompletes()
            throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("creation-live-owner", "thaap_creation_live_owner"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "creation-live-owner", merchant.getApiKeyPrefix());
        String idempotencyKey = "creation-live-owner-key";
        String url = "https://merchant.example/live-owner";
        Instant startedAt = Instant.parse("2026-07-11T08:00:00Z");
        MutableClock mutableClock = new MutableClock(startedAt);
        WebhookEndpointService fixedClockService = endpointService(
                sensitiveValueHasher, mutableClock);
        CountDownLatch blockerInserted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    UUID blockerEndpointId = UUID.randomUUID();
                    String rawSecret = WebhookSigningSecret.generate();
                    WebhookEncryptedSecret encryptedSecret = secretCipher.encrypt(
                            rawSecret, merchant.getId(), blockerEndpointId);
                    endpointRepository.saveAndFlush(new WebhookEndpoint(
                            blockerEndpointId, merchant.getId(), url, rawSecret,
                            encryptedSecret, startedAt));
                    blockerInserted.countDown();
                    await(releaseBlocker);
                    status.setRollbackOnly();
                });
                return null;
            });
            assertThat(blockerInserted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            Future<WebhookEndpointService.EndpointSecretResult> first = executor.submit(() ->
                    fixedClockService.create(
                            context, idempotencyKey, url,
                            Set.of("refund.created"), "live-owner-first"));
            UUID recordId = awaitSingleCreationRecordId();
            awaitCreationRecordLockConflict(recordId);
            awaitEndpointInsertLockWait();

            mutableClock.set(startedAt.plusSeconds(31));
            Future<WebhookEndpointService.EndpointSecretResult> second = executor.submit(() ->
                    fixedClockService.create(
                            context, idempotencyKey, url,
                            Set.of("refund.created"), "live-owner-second"));
            awaitCreationRecordLockWait();

            releaseBlocker.countDown();
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS);
            WebhookEndpointService.EndpointSecretResult created =
                    first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            WebhookEndpointService.EndpointSecretResult replayed =
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(replayed.endpoint().getId()).isEqualTo(created.endpoint().getId());
            assertThat(replayed.rawSigningSecret()).isEqualTo(created.rawSigningSecret());
            assertThat(endpointRepository.count()).isEqualTo(1);
            assertThat(creationRecordRepository.findById(recordId).orElseThrow().getStatus())
                    .isEqualTo(WebhookEndpointCreationStatus.COMPLETED);
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void rotationWaitsUntilCurrentGenerationSignatureIsCryptographicallyUsed()
            throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("sign-rotation-order", "thaap_sign_rotation_order"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "sign-rotation-order", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "sign-rotation-create", "https://merchant.example/sign-order",
                Set.of("refund.created"), "sign-rotation-create");
        UUID generation = created.endpoint().getSigningSecretGeneration();
        String payload = "{\"id\":\"" + UUID.randomUUID() + "\"}";
        Instant signatureTime = Instant.parse("2026-07-11T08:30:00Z");
        CountDownLatch signerOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseSigner = new CountDownLatch(1);
        CountDownLatch rotationStarted = new CountDownLatch(1);
        AtomicInteger rotationBackendPid = new AtomicInteger();
        WebhookSigner blockingSigner = new WebhookSigner() {
            @Override
            public WebhookSignature sign(String rawSecret, String signedPayload, Instant attemptTime) {
                signerOwnsEndpointLock.countDown();
                await(releaseSigner);
                return super.sign(rawSecret, signedPayload, attemptTime);
            }
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<WebhookSignature> signatureFuture = executor.submit(() ->
                    endpointSecretService.signForDelivery(
                            merchant.getId(), created.endpoint().getId(), generation,
                            payload, signatureTime, blockingSigner));
            assertThat(signerOwnsEndpointLock.await(
                    5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitEndpointLockConflict(created.endpoint().getId());

            Future<WebhookEndpointService.EndpointSecretResult> rotation = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        rotationBackendPid.set(currentBackendPid());
                        rotationStarted.countDown();
                        return endpointService.rotateSecret(
                                context, created.endpoint().getId(), "sign-rotation-action");
                    }));
            assertThat(rotationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitBackendLockWait(rotationBackendPid.get());

            releaseSigner.countDown();
            WebhookSignature signature = signatureFuture.get(
                    5, java.util.concurrent.TimeUnit.SECONDS);
            rotation.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(webhookSigner.verify(
                    created.rawSigningSecret(), payload,
                    signature.timestampSeconds(), signature.value())).isTrue();
            WebhookEndpoint rotated = endpointRepository.findById(
                    created.endpoint().getId()).orElseThrow();
            assertThat(rotated.getPreviousSigningSecretGeneration()).isEqualTo(generation);
        } finally {
            releaseSigner.countDown();
        }
    }

    @Test
    void previousGenerationWaitingForEndpointLockIsRejectedAtActualUseExpiry()
            throws Exception {
        Instant rotationTime = Instant.parse("2026-07-11T09:00:00Z");
        MutableClock mutableClock = new MutableClock(rotationTime);
        WebhookEndpointService clockedEndpointService = endpointService(
                sensitiveValueHasher, mutableClock);
        WebhookEndpointSecretService clockedSecretService =
                new WebhookEndpointSecretService(
                        endpointRepository, secretCipher, mutableClock);
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("sign-wait-expiry", "thaap_sign_wait_expiry"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "sign-wait-expiry", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = clockedEndpointService.create(
                context, "sign-wait-expiry-create",
                "https://merchant.example/sign-wait-expiry",
                Set.of("refund.created"), "sign-wait-expiry-create");
        UUID previousGeneration = created.endpoint().getSigningSecretGeneration();
        WebhookEndpointService.EndpointSecretResult rotated =
                new TransactionTemplate(transactionManager).execute(status ->
                        clockedEndpointService.rotateSecret(
                                context, created.endpoint().getId(),
                                "sign-wait-expiry-rotate"));
        Instant expiry = rotated.endpoint().getPreviousSigningSecretExpiresAt();
        CountDownLatch blockerOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger signerCalls = new AtomicInteger();
        WebhookSigner countingSigner = new WebhookSigner() {
            @Override
            public WebhookSignature sign(
                    String rawSecret, String payload, Instant attemptTime) {
                signerCalls.incrementAndGet();
                return super.sign(rawSecret, payload, attemptTime);
            }
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointRepository.findByIdAndMerchantIdForUpdate(
                            created.endpoint().getId(), merchant.getId()).orElseThrow();
                    blockerOwnsEndpointLock.countDown();
                    await(releaseBlocker);
                });
                return null;
            });
            assertThat(blockerOwnsEndpointLock.await(
                    5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            Future<WebhookSignature> signature = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status ->
                            clockedSecretService.signForDelivery(
                                    merchant.getId(), created.endpoint().getId(),
                                    previousGeneration,
                                    "{\"id\":\"" + UUID.randomUUID() + "\"}",
                                    rotationTime, countingSigner)));
            awaitEndpointLockWait();

            mutableClock.set(expiry);
            releaseBlocker.countDown();
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> signature.get(
                    5, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseInstanceOf(WebhookStaleSecretGenerationException.class);
            assertThat(signerCalls).hasValue(0);
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void rollingHashPepperVersionsSerializeAndReplayOneWebhookEndpoint()
            throws Exception {
        String firstPepper = "rolling-webhook-pepper-v1";
        String secondPepper = "rolling-webhook-pepper-v2";
        String firstEncoded = Base64.getEncoder().encodeToString(
                firstPepper.getBytes(StandardCharsets.UTF_8));
        String secondEncoded = Base64.getEncoder().encodeToString(
                secondPepper.getBytes(StandardCharsets.UTF_8));
        WebhookEndpointService versionTwoService = endpointService(
                new SensitiveValueHasher(
                        secondPepper, "rolling-v2", "rolling-v1=" + firstEncoded));
        WebhookEndpointService versionOneService = endpointService(
                new SensitiveValueHasher(
                        firstPepper, "rolling-v1", "rolling-v2=" + secondEncoded));
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("rolling-webhook", "thaap_rolling_webhook"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "rolling-webhook", merchant.getApiKeyPrefix());
        String idempotencyKey = "rolling-webhook-key";
        String url = "https://merchant.example/rolling-webhook";
        CountDownLatch firstOwnsCompletionLock = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        AtomicReference<WebhookEndpointService.EndpointSecretResult> firstResult =
                new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    firstResult.set(versionTwoService.create(
                            context, idempotencyKey, url,
                            Set.of("refund.created"), "rolling-v2-create"));
                    firstOwnsCompletionLock.countDown();
                    await(releaseFirstCommit);
                });
                return null;
            });

            firstOwnsCompletionLock.await();
            UUID recordId = jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM webhook_endpoint_creation_records
                    """, UUID.class);
            awaitCreationRecordLockConflict(recordId);

            Future<WebhookEndpointService.EndpointSecretResult> second =
                    executor.submit(() -> versionOneService.create(
                            context, idempotencyKey, url,
                            Set.of("refund.created"), "rolling-v1-replay"));
            awaitCreationRecordLockWait();

            releaseFirstCommit.countDown();
            first.get();
            WebhookEndpointService.EndpointSecretResult replayed = second.get();

            assertThat(replayed.endpoint().getId())
                    .isEqualTo(firstResult.get().endpoint().getId());
            assertThat(replayed.rawSigningSecret())
                    .isEqualTo(firstResult.get().rawSigningSecret());
            assertThat(endpointRepository.count()).isEqualTo(1);
            assertThat(creationRecordRepository.count()).isEqualTo(1);
            assertThat(creationIdentityRepository.count()).isEqualTo(2);
        } finally {
            releaseFirstCommit.countDown();
        }
    }

    @Test
    void concurrentWorkersCreateOneClaimAndOneAttemptNumberWinner() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("claim-race", "thaap_claim_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "claim-race", merchant.getApiKeyPrefix());
        endpointService.create(context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "claim-race-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant now = Instant.now();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<WebhookDeliveryClaim>> first = executor.submit(() -> claim(ready, start, now));
            Future<List<WebhookDeliveryClaim>> second = executor.submit(() -> claim(ready, start, now));
            ready.await();
            start.countDown();

            List<WebhookDeliveryClaim> firstClaims = first.get();
            List<WebhookDeliveryClaim> secondClaims = second.get();
            assertThat(firstClaims.size() + secondClaims.size()).isEqualTo(1);
        }

        WebhookDelivery claimed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        assertThat(claimed.getClaimToken()).isNotNull();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1))
                .get()
                .extracting(WebhookDeliveryAttempt::getOutcome)
                .isEqualTo(WebhookAttemptOutcome.IN_PROGRESS);
        assertThat(attemptRepository.count()).isEqualTo(1);
    }

    @Test
    void durableCursorPreventsNoisyMerchantAndEndpointStarvation() {
        Merchant noisyMerchant = merchantRepository.saveAndFlush(
                new Merchant("noisy-merchant", "thaap_noisy_merchant"));
        Merchant quietMerchant = merchantRepository.saveAndFlush(
                new Merchant("quiet-merchant", "thaap_quiet_merchant"));
        MerchantContext noisyContext = new MerchantContext(
                noisyMerchant.getId(), "noisy-merchant", noisyMerchant.getApiKeyPrefix());
        MerchantContext quietContext = new MerchantContext(
                quietMerchant.getId(), "quiet-merchant", quietMerchant.getApiKeyPrefix());
        endpointService.create(
                noisyContext, "noisy-first", "https://noisy.example/first",
                Set.of("refund.created"), "noisy-first");
        endpointService.create(
                noisyContext, "noisy-second", "https://noisy.example/second",
                Set.of("refund.created"), "noisy-second");
        endpointService.create(
                quietContext, "quiet-endpoint", "https://quiet.example/webhook",
                Set.of("refund.created"), "quiet-endpoint");

        for (int index = 0; index < 5; index++) {
            createDeliveries(noisyMerchant.getId());
        }
        createDeliveries(quietMerchant.getId());
        Instant claimAt = Instant.now().plusSeconds(1);

        List<WebhookDeliveryClaim> merchantRound = claimService.claimDue(claimAt, 2);

        assertThat(merchantRound).hasSize(2);
        assertThat(merchantRound)
                .extracting(WebhookDeliveryClaim::merchantId)
                .containsExactlyInAnyOrder(noisyMerchant.getId(), quietMerchant.getId());
        assertThat(merchantRound)
                .extracting(WebhookDeliveryClaim::deliveryId)
                .doesNotHaveDuplicates();

        databaseCleaner.clean();
        Merchant endpointMerchant = merchantRepository.saveAndFlush(
                new Merchant("endpoint-fairness", "thaap_endpoint_fairness"));
        MerchantContext endpointContext = new MerchantContext(
                endpointMerchant.getId(), "endpoint-fairness", endpointMerchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult first = endpointService.create(
                endpointContext, "endpoint-first", "https://endpoint.example/first",
                Set.of("refund.created"), "endpoint-first");
        WebhookEndpointService.EndpointSecretResult second = endpointService.create(
                endpointContext, "endpoint-second", "https://endpoint.example/second",
                Set.of("refund.created"), "endpoint-second");
        createDeliveries(endpointMerchant.getId());

        List<WebhookDeliveryClaim> endpointRound = claimService.claimDue(
                Instant.now().plusSeconds(1), 2);

        assertThat(endpointRound).hasSize(2);
        assertThat(endpointRound)
                .extracting(WebhookDeliveryClaim::endpointId)
                .containsExactlyInAnyOrder(first.endpoint().getId(), second.endpoint().getId());
    }

    @Test
    void forcedRotationBeforeDisableEndsDisabled() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("rotate-before-disable", "thaap_rotate_before_disable"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "rotate-before-disable", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "endpoint-state-create");
        CountDownLatch rotationOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> rotation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointService.rotateSecret(
                            context, created.endpoint().getId(), "endpoint-state-rotate");
                    rotationOwnsEndpointLock.countDown();
                    await(releaseRotation);
                });
                return null;
            });
            rotationOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(
                            context, created.endpoint().getId(), "endpoint-state-disable");
                });
                return null;
            });
            disableStarted.await();
            awaitBackendLockWait(disableBackendPid.get());

            releaseRotation.countDown();
            rotation.get();
            disable.get();
        } finally {
            releaseRotation.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
    }

    @Test
    void forcedDisableBeforeRotationRejectsReactivation() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-before-rotate", "thaap_disable_before_rotate"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-before-rotate", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "endpoint-state-create");
        CountDownLatch disableOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch rotationStarted = new CountDownLatch(1);
        AtomicInteger rotationBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointService.disable(
                            context, created.endpoint().getId(), "endpoint-state-disable");
                    disableOwnsEndpointLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            disableOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> rotation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    rotationBackendPid.set(currentBackendPid());
                    rotationStarted.countDown();
                    endpointService.rotateSecret(
                            context, created.endpoint().getId(), "endpoint-state-rotate");
                });
                return null;
            });
            rotationStarted.await();
            awaitBackendLockWait(rotationBackendPid.get());

            releaseDisable.countDown();
            disable.get();
            assertThatThrownBy(rotation::get)
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .rootCause()
                    .satisfies(exception -> assertThat(
                            ((org.springframework.web.server.ResponseStatusException) exception)
                                    .getStatusCode().value())
                            .isEqualTo(409));
        } finally {
            releaseDisable.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
    }

    @Test
    void forcedRoutingBeforeDisableExhaustsTheCommittedDelivery() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("route-before-disable", "thaap_route_before_disable"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "route-before-disable", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "delivery-disable-create");
        CountDownLatch routingOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseRouting = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deliveryCreation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    deliveryService.createDeliveriesInCurrentTransaction(
                            merchant.getId(), "refund.created", UUID.randomUUID());
                    routingOwnsEndpointLock.countDown();
                    await(releaseRouting);
                });
                return null;
            });
            routingOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(
                            context, created.endpoint().getId(), "delivery-disable-action");
                });
                return null;
            });
            disableStarted.await();
            awaitBackendLockWait(disableBackendPid.get());

            releaseRouting.countDown();
            deliveryCreation.get();
            disable.get();
        } finally {
            releaseRouting.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(deliveryRepository.findAll()).singleElement()
                .satisfies(delivery -> assertThat(delivery.getStatus())
                        .isEqualTo(WebhookDeliveryStatus.EXHAUSTED));
        assertThat(claimService.claimDue(Instant.now().plusSeconds(60), 10)).isEmpty();
    }

    @Test
    void forcedDisableBeforeRoutingCreatesNoEventOrDelivery() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-before-route", "thaap_disable_before_route"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-before-route", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "delivery-disable-create");
        CountDownLatch disableOwnsEndpointLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch routingStarted = new CountDownLatch(1);
        AtomicInteger routingBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    endpointService.disable(
                            context, created.endpoint().getId(), "delivery-disable-action");
                    disableOwnsEndpointLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            disableOwnsEndpointLock.await();
            awaitEndpointLockConflict(created.endpoint().getId());
            Future<?> deliveryCreation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    routingBackendPid.set(currentBackendPid());
                    routingStarted.countDown();
                        deliveryService.createDeliveriesInCurrentTransaction(
                                merchant.getId(), "refund.created", UUID.randomUUID());
                });
                return null;
            });
            routingStarted.await();
            awaitBackendLockWait(routingBackendPid.get());

            releaseDisable.countDown();
            disable.get();
            deliveryCreation.get();
        } finally {
            releaseDisable.countDown();
        }

        assertThat(endpointRepository.findById(created.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(deliveryRepository.count()).isZero();
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void concurrentRetryFinalizationAndDisableCannotLeaveRetryScheduled() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("finalize-disable-race", "thaap_finalize_disable_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "finalize-disable-race", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "finalize-disable-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = Instant.now();
        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt)).isTrue();
        CountDownLatch deliveryLocked = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    deliveryRepository.findByIdForUpdate(delivery.getId()).orElseThrow();
                    deliveryLocked.countDown();
                    await(releaseDelivery);
                });
                return null;
            });
            deliveryLocked.await();

            Future<WebhookDeliveryFinalizationResult> finalization = executor.submit(() ->
                    finalizationService.failRetryably(
                            claim, claimedAt.plusSeconds(1), true, 503,
                            WebhookDeliveryErrorCode.HTTP_NON_SUCCESS));
            awaitEndpointLockConflict(endpoint.endpoint().getId());
            Future<?> disable = executor.submit(() -> {
                endpointService.disable(
                        context, endpoint.endpoint().getId(), "finalize-disable-action");
                return null;
            });

            releaseDelivery.countDown();
            lockHolder.get();
            assertThat(finalization.get().finalized()).isTrue();
            disable.get();
        } finally {
            releaseDelivery.countDown();
        }

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(claimService.claimDue(claimedAt.plusSeconds(600), 1)).isEmpty();
    }

    @Test
    void disabledEndpointStillRecoversExpiredInFlightAttemptOnPostgreSql() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-crash-pg", "thaap_disable_crash_pg"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-crash-pg", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-crash-pg-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = Instant.now().minusSeconds(60)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt.plusSeconds(1))).isTrue();
        endpointService.disable(context, endpoint.endpoint().getId(), "disable-crash-pg-action");

        assertThat(claimService.claimDue(claimedAt.plusSeconds(32), 1)).isEmpty();

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(attempt.getHttpRequestSent()).isNull();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 2))
                .isEmpty();
    }

    @Test
    void concurrentClaimDoesNotInflateBulkDisableMetrics() throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-metric-race", "thaap_disable_metric_race"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "disable-metric-race", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-metric-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        double terminalBefore = meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count();
        double disabledBefore = meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "ENDPOINT_DISABLED").count();
        CountDownLatch claimOwnsDeliveryLock = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> claim = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    int updated = jdbcTemplate.update("""
                            UPDATE webhook_deliveries
                            SET status = 'DELIVERING',
                                attempt_count = 1,
                                generation_attempt_count = 1,
                                last_attempt_at = ?,
                                claim_token = ?,
                                lease_expires_at = ?
                            WHERE id = ?
                            """,
                            java.sql.Timestamp.from(Instant.now()),
                            UUID.randomUUID(),
                            java.sql.Timestamp.from(Instant.now().plusSeconds(30)),
                            delivery.getId());
                    assertThat(updated).isEqualTo(1);
                    claimOwnsDeliveryLock.countDown();
                    await(releaseClaim);
                });
                return null;
            });
            claimOwnsDeliveryLock.await();

            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(
                            context, endpoint.endpoint().getId(), "disable-metric-action");
                });
                return null;
            });
            disableStarted.await();
            awaitBackendLockWait(disableBackendPid.get());

            releaseClaim.countDown();
            claim.get();
            disable.get();
        } finally {
            releaseClaim.countDown();
        }

        assertThat(endpointRepository.findById(endpoint.endpoint().getId()).orElseThrow()
                .getOperationalStatus()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count() - terminalBefore)
                .isZero();
        assertThat(meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "ENDPOINT_DISABLED").count() - disabledBefore)
                .isZero();
    }

    @Test
    void forcedRedeliveryBeforeDisableSerializesEndpointThenDeliveryAndLeavesNoSendableWork()
            throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("redelivery-disable-order", "thaap_redelivery_disable_order"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "redelivery-disable-order", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "redelivery-disable-create", "http://127.0.0.1:9191/redelivery-disable",
                Set.of("refund.created"), "redelivery-disable-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        delivery.exhaustWithoutClaim(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        deliveryRepository.saveAndFlush(delivery);
        CountDownLatch redeliveryOwnsLocks = new CountDownLatch(1);
        CountDownLatch releaseRedelivery = new CountDownLatch(1);
        CountDownLatch disableStarted = new CountDownLatch(1);
        AtomicInteger disableBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> redelivery = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    operationsService.redeliver(context, delivery.getId(),
                            WebhookRedeliveryReason.RECEIVER_REPAIRED, "redelivery-first");
                    redeliveryOwnsLocks.countDown();
                    await(releaseRedelivery);
                });
                return null;
            });
            assertThat(redeliveryOwnsLocks.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitEndpointLockConflict(endpoint.endpoint().getId());

            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    disableBackendPid.set(currentBackendPid());
                    disableStarted.countDown();
                    endpointService.disable(context, endpoint.endpoint().getId(), "disable-second");
                });
                return null;
            });
            assertThat(disableStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitBackendLockWait(disableBackendPid.get());

            releaseRedelivery.countDown();
            redelivery.get(5, java.util.concurrent.TimeUnit.SECONDS);
            disable.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseRedelivery.countDown();
        }

        WebhookDelivery reconciled = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reconciled.getDeliveryGeneration()).isEqualTo(2);
        assertThat(reconciled.getGenerationAttemptCount()).isZero();
        assertThat(reconciled.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(reconciled.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM webhook_redelivery_requests
                 WHERE webhook_delivery_id = ? AND requested_generation = 2
                """, Integer.class, delivery.getId())).isOne();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE webhook_redelivery_requests SET request_reason = 'MANUAL_RETRY'
                 WHERE webhook_delivery_id = ?
                """, delivery.getId()))
                .hasMessageContaining("Webhook operation history is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE webhook_deliveries SET delivery_generation = 4 WHERE id = ?
                """, delivery.getId()))
                .hasMessageContaining("Invalid webhook manual redelivery generation transition");
        assertThat(claimService.claimDue(Instant.now().plusSeconds(60), 10)).isEmpty();
    }

    @Test
    void forcedOperatorDisableBeforeRedeliveryRejectsAuthenticatedInFlightAndFutureRequests()
            throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("operator-disable-redelivery", "thaap_operator_disable_redelivery"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                merchantContext, "operator-disable-redelivery-endpoint",
                "http://127.0.0.1:9191/operator-disable-redelivery",
                Set.of("refund.created"), "operator-disable-redelivery-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        delivery.exhaustWithoutClaim(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        deliveryRepository.saveAndFlush(delivery);
        RiskOperatorService.CreationResult manager = riskOperatorService.create(
                merchantContext, "operator-disable-redelivery-manager",
                RiskOperatorRole.RISK_MANAGER, "operator-disable-redelivery-manager");
        RiskOperatorContext managerContext = new RiskOperatorContext(
                merchant.getId(), manager.operator().getId(), manager.operator().getOperatorCode(),
                manager.operator().getRole(), manager.credentialPrefix());
        CountDownLatch disableOwnsActorLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch redeliveryStarted = new CountDownLatch(1);
        AtomicInteger redeliveryBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    riskOperatorService.disable(merchantContext, manager.operator().getId(),
                            "operator-disable-redelivery-disable");
                    disableOwnsActorLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            assertThat(disableOwnsActorLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRiskOperatorLockConflict(manager.operator().getId());

            Future<?> redelivery = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        redeliveryBackendPid.set(currentBackendPid());
                        redeliveryStarted.countDown();
                        operationsService.redeliver(managerContext, delivery.getId(),
                                WebhookRedeliveryReason.RECEIVER_REPAIRED,
                                "operator-disable-redelivery-in-flight");
                    }));
            assertThat(redeliveryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBackendLockWait(redeliveryBackendPid.get());

            releaseDisable.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertNotFound(redelivery);
        } finally {
            releaseDisable.countDown();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM risk_operators WHERE id = ?", String.class,
                manager.operator().getId())).isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT disabled_at IS NOT NULL FROM risk_operator_credentials
                 WHERE merchant_id = ? AND risk_operator_id = ?
                """, Boolean.class, merchant.getId(), manager.operator().getId())).isTrue();
        WebhookDelivery unchanged = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(unchanged.getDeliveryGeneration()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_redelivery_requests",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE action = 'webhook.redelivery.requested'
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> operationsService.redeliver(managerContext, delivery.getId(),
                WebhookRedeliveryReason.RECEIVER_REPAIRED,
                "operator-disable-redelivery-future"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        assertThat(endpointRepository.findById(endpoint.endpoint().getId()).orElseThrow().isActive())
                .isTrue();
    }

    @Test
    void verificationHttpRunsWithoutDatabaseLocksAndRotationFencesItsFinalization()
            throws Exception {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("verification-rotation-fence", "thaap_verification_rotation_fence"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "verification-rotation-fence", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "verification-rotation-create",
                "http://127.0.0.1:9191/verification-rotation",
                Set.of("refund.created"), "verification-rotation-create");
        CountDownLatch httpStarted = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        when(transport.send(any())).thenAnswer(invocation -> {
            WebhookHttpRequest request = invocation.getArgument(0);
            String token = objectMapper.readTree(request.payload()).get("challenge").asText();
            httpStarted.countDown();
            await(releaseHttp);
            return new WebhookHttpResponse(204, token);
        });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<WebhookEndpointVerificationService.ChallengeView> verification = executor.submit(() ->
                    verificationService.verify(context, endpoint.endpoint().getId(),
                            "verification-rotation-key", "verification-rotation-request"));
            assertThat(httpStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            endpointService.rotateSecret(
                    context, endpoint.endpoint().getId(), "rotation-during-verification");
            releaseHttp.countDown();

            WebhookEndpointVerificationService.ChallengeView result =
                    verification.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.lifecycleStatus()).isEqualTo(WebhookEndpointVerificationStatus.FAILED);
            assertThat(result.failureCode()).isEqualTo("ENDPOINT_CHANGED");
        } finally {
            releaseHttp.countDown();
        }

        assertThat(endpointRepository.findById(endpoint.endpoint().getId()).orElseThrow()
                .getVerificationStatus()).isEqualTo(WebhookEndpointVerificationStatus.NOT_REQUESTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM webhook_endpoint_verification_events
                 WHERE event_type = 'FAILED' AND outcome_code = 'ENDPOINT_CHANGED'
                """, Integer.class)).isOne();
        UUID verificationId = jdbcTemplate.queryForObject("""
                SELECT id FROM webhook_endpoint_verifications
                 WHERE webhook_endpoint_id = ?
                """, UUID.class, endpoint.endpoint().getId());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE webhook_endpoint_verifications SET failure_code = 'TOKEN_MISMATCH'
                 WHERE id = ?
                """, verificationId))
                .hasMessageContaining("Terminal webhook endpoint verification is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM webhook_endpoint_verification_events
                 WHERE webhook_endpoint_verification_id = ?
                """, verificationId))
                .hasMessageContaining("Webhook operation history is append-only");
    }

    @Test
    void terminalVerificationWinnerAfterSigningRevokesDispatchBeforeAnyNetworkSideEffect() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("verification-terminal-fence", "thaap_verification_terminal_fence"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "verification-terminal-fence", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "verification-terminal-create",
                "http://127.0.0.1:9191/verification-terminal",
                Set.of("refund.created"), "verification-terminal-create");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        doAnswer(invocation -> {
            WebhookEndpointVerificationService.PreparedChallenge prepared =
                    invocation.getArgument(1);
            Instant terminalAt = Instant.now();
            transaction.executeWithoutResult(status -> {
                jdbcTemplate.update("""
                        UPDATE webhook_endpoints
                           SET verification_status = 'EXPIRED', pending_verification_id = NULL
                         WHERE merchant_id = ? AND id = ?
                        """, merchant.getId(), prepared.endpointId());
                jdbcTemplate.update("""
                        UPDATE webhook_endpoint_verifications
                           SET lifecycle_status = 'EXPIRED', failure_code = 'EXPIRED', completed_at = ?
                         WHERE merchant_id = ? AND id = ?
                        """, java.sql.Timestamp.from(terminalAt), merchant.getId(), prepared.id());
                jdbcTemplate.update("""
                        INSERT INTO webhook_endpoint_verification_events
                            (id, merchant_id, webhook_endpoint_verification_id,
                             event_sequence, event_type, outcome_code, occurred_at)
                        VALUES (?, ?, ?, 2, 'EXPIRED', 'EXPIRED', ?)
                        """, UUID.randomUUID(), merchant.getId(), prepared.id(),
                        java.sql.Timestamp.from(terminalAt));
            });
            return invocation.callRealMethod();
        }).when(verificationService).acquireDispatchPermit(
                eq(merchant.getId()),
                any(WebhookEndpointVerificationService.PreparedChallenge.class));

        WebhookEndpointVerificationService.ChallengeView result = verificationService.verify(
                context, endpoint.endpoint().getId(),
                "verification-terminal-key", "verification-terminal-request");

        assertThat(result.lifecycleStatus()).isEqualTo(WebhookEndpointVerificationStatus.EXPIRED);
        assertThat(result.failureCode()).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT dispatch_started_at IS NULL
                  FROM webhook_endpoint_verifications
                 WHERE id = ?
                """, Boolean.class, result.id())).isTrue();
        verify(endpointSecretService).signForDelivery(eq(merchant.getId()),
                eq(endpoint.endpoint().getId()), any(UUID.class), any(String.class),
                any(Instant.class), any(WebhookSigner.class));
        verifyNoInteractions(transport);
    }

    private void awaitEndpointLockConflict(UUID endpointId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT id
                            FROM webhook_endpoints
                            WHERE id = ?
                            FOR UPDATE NOWAIT
                            """)) {
                connection.setAutoCommit(false);
                statement.setObject(1, endpointId);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected endpoint lock was not observed");
    }

    private WebhookEndpointService endpointService(SensitiveValueHasher hasher) {
        return endpointService(hasher, Clock.systemUTC());
    }

    private WebhookEndpointService endpointService(
            SensitiveValueHasher hasher, Clock serviceClock) {
        return new WebhookEndpointService(
                endpointRepository,
                creationRecordRepository,
                creationIdentityRepository,
                deliveryRepository,
                subscriptionStore,
                secretCipher,
                auditLogService,
                webhookMetrics,
                serviceClock,
                hasher,
                transactionManager,
                Duration.ofMinutes(15),
                org.mockito.Mockito.mock(com.thaap.payments.risk.RiskOperationsEventProducer.class));
    }

    private UUID awaitSingleCreationRecordId() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<UUID> ids = jdbcTemplate.query(
                    "SELECT id FROM webhook_endpoint_creation_records",
                    (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class));
            if (ids.size() == 1) {
                return ids.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Endpoint creation reservation was not persisted");
    }

    private void awaitEndpointInsertLockWait() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock'
                          AND query LIKE 'insert into webhook_endpoints%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Endpoint insertion did not wait on the unique URL boundary");
    }

    private void awaitCreationRecordLockConflict(UUID creationRecordId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT id
                            FROM webhook_endpoint_creation_records
                            WHERE id = ?
                            FOR UPDATE NOWAIT
                            """)) {
                connection.setAutoCommit(false);
                statement.setObject(1, creationRecordId);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected endpoint creation record lock was not observed");
    }

    private void awaitRiskOperatorLockConflict(UUID operatorId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT id FROM risk_operators WHERE id = ? FOR UPDATE NOWAIT
                            """)) {
                connection.setAutoCommit(false);
                statement.setObject(1, operatorId);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected risk operator row lock was not observed");
    }

    private void awaitCreationRecordLockWait() throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock'
                          AND query LIKE '%webhook_endpoint_creation_records%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Competing endpoint creation did not wait on the reservation lock");
    }

    private void awaitEndpointLockWait() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock'
                          AND query LIKE '%webhook_endpoints%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Previous-generation signer did not wait on the endpoint lock");
    }

    private int currentBackendPid() {
        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (backendPid == null) {
            throw new IllegalStateException("PostgreSQL backend PID was unavailable");
        }
        return backendPid;
    }

    private void awaitBackendLockWait(int backendPid) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE pid = ?
                          AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing PostgreSQL transaction did not wait on the expected lock");
    }

    private void assertNotFound(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected disabled-actor operation to fail");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause.getCause() != null && !(cause instanceof ResponseStatusException)) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) cause).getStatusCode().value()).isEqualTo(404);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency test was interrupted", exception);
        }
    }

    private List<WebhookDeliveryClaim> claim(CountDownLatch ready, CountDownLatch start, Instant now)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return claimService.claimDue(now, 1);
    }

    private void createDeliveries(UUID merchantId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchantId, "refund.created", UUID.randomUUID()));
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        private void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
