package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.audit.AuditLog;
import com.thaap.payments.audit.AuditLogRepository;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.time.DatabaseInstant;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest
class WebhookEndpointOperationsTests {

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
    private WebhookEndpointSubscriptionStore subscriptionStore;

    @Autowired
    private WebhookSecretCipher secretCipher;

    @Autowired
    private WebhookEndpointSecretService endpointSecretService;

    @Autowired
    private WebhookSigner webhookSigner;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WebhookMetrics webhookMetrics;

    @Autowired
    private SensitiveValueHasher sensitiveValueHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @AfterEach
    void cleanDatabaseAfterTest() {
        databaseCleaner.clean();
    }

    @Test
    void createFilterRotateDisableAndAuditStayTenantScopedAndSecretSafe() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_webhook_a"));
        Merchant otherMerchant = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_webhook_b"));
        MerchantContext merchantContext = context(merchant, "merchant-a");
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                merchantContext, "endpoint-create-a", "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "request-create");

        WebhookEndpoint stored = endpointRepository.findById(created.endpoint().getId()).orElseThrow();
        assertThat(stored.getOperationalStatus()).isEqualTo(WebhookEndpointStatus.ACTIVE);
        assertThat(stored.getSigningSecretCiphertext()).isNotEqualTo(created.rawSigningSecret());
        assertThat(stored.getSigningSecretHash()).doesNotContain(created.rawSigningSecret());
        assertThat(secretCipher.decrypt(stored)).isEqualTo(created.rawSigningSecret());
        assertThat(subscriptionStore.findWireValues(merchant.getId(), stored.getId()))
                .containsExactly("refund.created");

        createDeliveries(merchant.getId(), "payment_intent.created", UUID.randomUUID());
        assertThat(eventRepository.count()).isZero();
        createDeliveries(merchant.getId(), "refund.created", UUID.randomUUID());
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);

        WebhookEndpointService.EndpointSecretResult rotated = endpointService.rotateSecret(
                merchantContext, stored.getId(), "request-rotate");
        assertThat(rotated.rawSigningSecret()).isNotEqualTo(created.rawSigningSecret());
        assertThat(secretCipher.decrypt(endpointRepository.findById(stored.getId()).orElseThrow()))
                .isEqualTo(rotated.rawSigningSecret());

        double terminalBefore = meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count();
        double disabledBefore = meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "ENDPOINT_DISABLED").count();
        endpointService.disable(merchantContext, stored.getId(), "request-disable");
        WebhookDelivery queued = deliveryRepository.findAll().getFirst();
        assertThat(queued.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(queued.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        createDeliveries(merchant.getId(), "refund.created", UUID.randomUUID());
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count() - terminalBefore)
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "ENDPOINT_DISABLED").count() - disabledBefore)
                .isEqualTo(1.0);
        assertThatThrownBy(() -> endpointService.rotateSecret(
                merchantContext, stored.getId(), "disabled-rotation"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(endpointRepository.findById(stored.getId()).orElseThrow().getOperationalStatus())
                .isEqualTo(WebhookEndpointStatus.DISABLED);

        MerchantContext otherContext = context(otherMerchant, "merchant-b");
        assertThatThrownBy(() -> endpointService.rotateSecret(otherContext, stored.getId(), "cross-tenant"))
                .isInstanceOf(ResponseStatusException.class);

        List<AuditLog> auditLogs = auditLogRepository.findAll();
        assertThat(auditLogs).extracting(AuditLog::getAction)
                .containsExactlyInAnyOrder(
                        "webhook_endpoint.created",
                        "webhook_endpoint.secret_rotated",
                        "webhook_endpoint.disabled");
        assertThat(auditLogs).allSatisfy(log -> {
            assertThat(log.getMerchantId()).isEqualTo(merchant.getId());
            assertThat(String.valueOf(log.getBefore())).doesNotContain(created.rawSigningSecret());
            assertThat(log.getAfter().toString())
                    .doesNotContain(created.rawSigningSecret())
                    .doesNotContain(rotated.rawSigningSecret())
                    .doesNotContain("127.0.0.1");
        });
    }

    @Test
    void endpointCreationKeyBindingAndReplaySurviveHashPepperRotation() {
        String oldPepper = "webhook-idempotency-old-pepper";
        String newPepper = "webhook-idempotency-new-pepper";
        SensitiveValueHasher oldHasher = new SensitiveValueHasher(
                oldPepper, "webhook-v1", "");
        WebhookEndpointService oldService = endpointService(oldHasher);
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("webhook-rotation", "thaap_webhook_rotation"));
        MerchantContext merchantContext = context(merchant, "webhook-rotation");

        WebhookEndpointService.EndpointSecretResult original = oldService.create(
                merchantContext, "rotation-safe-endpoint-key",
                "https://merchant.example/rotation-safe",
                Set.of("refund.created"), "rotation-old");

        String previousKeyring = "webhook-v1="
                + Base64.getEncoder().encodeToString(
                        oldPepper.getBytes(StandardCharsets.UTF_8));
        SensitiveValueHasher rotatedHasher = new SensitiveValueHasher(
                newPepper, "webhook-v2", previousKeyring);
        WebhookEndpointService rotatedService = endpointService(rotatedHasher);
        WebhookEndpointService.EndpointSecretResult replay = rotatedService.create(
                merchantContext, "rotation-safe-endpoint-key",
                "https://merchant.example/rotation-safe",
                Set.of("refund.created"), "rotation-new");

        assertThat(replay.endpoint().getId()).isEqualTo(original.endpoint().getId());
        assertThat(replay.rawSigningSecret()).isEqualTo(original.rawSigningSecret());
        assertThat(endpointRepository.count()).isEqualTo(1);
        assertThat(creationRecordRepository.count()).isEqualTo(1);
        assertThat(creationIdentityRepository.count()).isEqualTo(2);

        assertThatThrownBy(() -> rotatedService.create(
                merchantContext, "rotation-safe-endpoint-key",
                "https://merchant.example/different",
                Set.of("refund.created"), "rotation-conflict"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(endpointRepository.count()).isEqualTo(1);

        WebhookEndpointCreationKeyConfiguration missingOldKey =
                new WebhookEndpointCreationKeyConfiguration(
                        creationRecordRepository,
                        new SensitiveValueHasher(newPepper, "webhook-v2", ""),
                        DatabaseInstant.tick(Clock.systemUTC()));
        assertThatThrownBy(missingOldKey::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook-v1");
    }

    @Test
    void expiredWebhookReplayDoesNotRetainADeadHashPepperForever() {
        String oldPepper = "expired-webhook-old-pepper";
        SensitiveValueHasher oldHasher = new SensitiveValueHasher(
                oldPepper, "expired-webhook-v1", "");
        WebhookEndpointService oldService = endpointService(oldHasher);
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("expired-webhook", "thaap_expired_webhook"));
        MerchantContext merchantContext = context(merchant, "expired-webhook");
        WebhookEndpointService.EndpointSecretResult original = oldService.create(
                merchantContext, "expired-reusable-key",
                "https://merchant.example/expired-original",
                Set.of("refund.created"), "expired-old");
        jdbcTemplate.update("""
                UPDATE webhook_endpoint_creation_records
                SET created_at = ?,
                    expires_at = ?
                WHERE webhook_endpoint_id = ?
                """, java.sql.Timestamp.from(DatabaseInstant.now().minusSeconds(120)),
                java.sql.Timestamp.from(DatabaseInstant.now().minusSeconds(1)),
                original.endpoint().getId());

        SensitiveValueHasher newHasher = new SensitiveValueHasher(
                "expired-webhook-new-pepper", "expired-webhook-v2", "");
        WebhookEndpointCreationKeyConfiguration retiredOldKey =
                new WebhookEndpointCreationKeyConfiguration(
                        creationRecordRepository, newHasher, DatabaseInstant.tick(Clock.systemUTC()));
        retiredOldKey.afterPropertiesSet();
        WebhookEndpointService.EndpointSecretResult replacement =
                endpointService(newHasher).create(
                        merchantContext, "expired-reusable-key",
                        "https://merchant.example/expired-replacement",
                        Set.of("refund.created"), "expired-new");

        assertThat(replacement.endpoint().getId())
                .isNotEqualTo(original.endpoint().getId());
        assertThat(endpointRepository.count()).isEqualTo(2);
        assertThat(creationRecordRepository.count()).isEqualTo(2);
        assertThat(creationIdentityRepository.count()).isEqualTo(2);
    }

    @Test
    void expiredWebhookCreationKeyStartsNewEpochWithoutPepperRotation() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("expired-webhook-same-pepper", "thaap_expired_webhook_same_pepper"));
        MerchantContext merchantContext = context(merchant, "expired-webhook-same-pepper");
        String idempotencyKey = "expired-same-pepper-key";
        WebhookEndpointService.EndpointSecretResult original = endpointService.create(
                merchantContext, idempotencyKey,
                "https://merchant.example/expired-same-pepper-original",
                Set.of("refund.created"), "expired-same-pepper-original");
        jdbcTemplate.update("""
                UPDATE webhook_endpoint_creation_records
                SET created_at = ?,
                    expires_at = ?
                WHERE webhook_endpoint_id = ?
                """, java.sql.Timestamp.from(DatabaseInstant.now().minusSeconds(120)),
                java.sql.Timestamp.from(DatabaseInstant.now().minusSeconds(1)),
                original.endpoint().getId());

        WebhookEndpointService.EndpointSecretResult replacement = endpointService.create(
                merchantContext, idempotencyKey,
                "https://merchant.example/expired-same-pepper-replacement",
                Set.of("refund.created"), "expired-same-pepper-replacement");

        assertThat(replacement.endpoint().getId()).isNotEqualTo(original.endpoint().getId());
        assertThat(replacement.rawSigningSecret()).isNotEqualTo(original.rawSigningSecret());
        assertThat(endpointRepository.count()).isEqualTo(2);
        assertThat(creationRecordRepository.count()).isEqualTo(1);
        assertThat(creationIdentityRepository.count()).isEqualTo(1);
    }

    @Test
    void legacyEndpointRotationActivatesEndpointWithRecoverableSecret() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("legacy-webhook", "thaap_legacy_webhook"));
        MerchantContext merchantContext = context(merchant, "legacy-webhook");
        WebhookEndpoint legacy = endpointRepository.saveAndFlush(
                new WebhookEndpoint(merchant.getId(), "http://127.0.0.1:9191/thaap"));
        assertThat(legacy.getOperationalStatus())
                .isEqualTo(WebhookEndpointStatus.REQUIRES_SECRET_ROTATION);

        WebhookEndpointService.EndpointSecretResult rotated = endpointService.rotateSecret(
                merchantContext, legacy.getId(), "legacy-secret-rotation");

        WebhookEndpoint active = endpointRepository.findById(legacy.getId()).orElseThrow();
        assertThat(active.getOperationalStatus()).isEqualTo(WebhookEndpointStatus.ACTIVE);
        assertThat(secretCipher.decrypt(active)).isEqualTo(rotated.rawSigningSecret());
        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .containsExactly("webhook_endpoint.secret_rotated");
    }

    @Test
    void endpointCreationReplayIsTenantScopedAndDuplicateUrlsAreExplicitlyRejected() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("idempotent-webhook", "thaap_idempotent_webhook"));
        Merchant otherMerchant = merchantRepository.saveAndFlush(
                new Merchant("other-idempotent-webhook", "thaap_other_idempotent_webhook"));
        MerchantContext merchantContext = context(merchant, "idempotent-webhook");
        MerchantContext otherContext = context(otherMerchant, "other-idempotent-webhook");
        String url = "https://merchant.example/webhook";

        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                merchantContext, "stable-create-key", url,
                Set.of("refund.created"), "create-first");
        WebhookEndpointService.EndpointSecretResult replayed = endpointService.create(
                merchantContext, "stable-create-key", url,
                Set.of("refund.created"), "create-replay");

        assertThat(replayed.endpoint().getId()).isEqualTo(created.endpoint().getId());
        assertThat(replayed.rawSigningSecret()).isEqualTo(created.rawSigningSecret());
        assertThat(endpointRepository.count()).isEqualTo(1);
        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .containsExactly("webhook_endpoint.created");

        assertThatThrownBy(() -> endpointService.create(
                merchantContext, "stable-create-key", "https://merchant.example/changed",
                Set.of("refund.created"), "create-changed"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> endpointService.create(
                merchantContext, "different-create-key", url,
                Set.of("refund.created"), "create-duplicate-url"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        WebhookEndpointService.EndpointSecretResult otherTenant = endpointService.create(
                otherContext, "stable-create-key", url,
                Set.of("refund.created"), "create-other-tenant");
        assertThat(otherTenant.endpoint().getMerchantId()).isEqualTo(otherMerchant.getId());
        assertThat(endpointRepository.count()).isEqualTo(2);
    }

    @Test
    void expiredEndpointCreationReservationIsReclaimedWithoutStealingActiveOwnership() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("reclaim-webhook", "thaap_reclaim_webhook"));
        MerchantContext merchantContext = context(merchant, "reclaim-webhook");
        String staleKey = "stale-create-key";
        String staleUrl = "https://merchant.example/reclaimed";
        Instant staleStartedAt = DatabaseInstant.now().minusSeconds(120);
        WebhookEndpointCreationRecord staleRecord = new WebhookEndpointCreationRecord(
                merchant.getId(),
                sensitiveValueHasher.hmacSha256("webhook.endpoint.idempotency", staleKey),
                sensitiveValueHasher.hmacSha256(
                        "webhook.endpoint.create", staleUrl + "\nrefund.created"),
                sensitiveValueHasher.currentKeyVersion(),
                UUID.randomUUID(), staleStartedAt.plusSeconds(30),
                staleStartedAt, staleStartedAt.plusSeconds(24 * 60 * 60));
        creationRecordRepository.saveAndFlush(staleRecord);

        WebhookEndpointService.EndpointSecretResult reclaimed = endpointService.create(
                merchantContext, staleKey, staleUrl,
                Set.of("refund.created"), "reclaim-stale");

        WebhookEndpointCreationRecord completed = creationRecordRepository.findById(staleRecord.getId())
                .orElseThrow();
        assertThat(reclaimed.endpoint().getUrl()).isEqualTo(staleUrl);
        assertThat(completed.getStatus()).isEqualTo(WebhookEndpointCreationStatus.COMPLETED);
        assertThat(completed.getWebhookEndpointId()).isEqualTo(reclaimed.endpoint().getId());
        assertThat(completed.getLeaseToken()).isNull();
        assertThat(completed.getLeaseExpiresAt()).isNull();

        String activeKey = "active-create-key";
        String activeUrl = "https://merchant.example/active-reservation";
        Instant now = DatabaseInstant.now();
        WebhookEndpointCreationRecord activeRecord = new WebhookEndpointCreationRecord(
                merchant.getId(),
                sensitiveValueHasher.hmacSha256("webhook.endpoint.idempotency", activeKey),
                sensitiveValueHasher.hmacSha256(
                        "webhook.endpoint.create", activeUrl + "\nrefund.created"),
                sensitiveValueHasher.currentKeyVersion(),
                UUID.randomUUID(), now.plusSeconds(30),
                now, now.plusSeconds(24 * 60 * 60));
        creationRecordRepository.saveAndFlush(activeRecord);

        assertThatThrownBy(() -> endpointService.create(
                merchantContext, activeKey, activeUrl,
                Set.of("refund.created"), "do-not-steal-active"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(creationRecordRepository.findById(activeRecord.getId()).orElseThrow().getLeaseToken())
                .isEqualTo(activeRecord.getLeaseToken());
    }

    @Test
    void requiredKeyInventoryIncludesCurrentPreviousAndReplayCiphertext() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("key-inventory", "thaap_key_inventory"));
        MerchantContext merchantContext = context(merchant, "key-inventory");
        WebhookEndpointService.EndpointSecretResult rotatedEndpoint = endpointService.create(
                merchantContext, "key-inventory-rotated",
                "https://merchant.example/key-inventory-rotated",
                Set.of("refund.created"), "key-inventory-rotated");
        endpointService.rotateSecret(
                merchantContext, rotatedEndpoint.endpoint().getId(),
                "key-inventory-rotate");
        WebhookEndpointService.EndpointSecretResult replayEndpoint = endpointService.create(
                merchantContext, "key-inventory-replay",
                "https://merchant.example/key-inventory-replay",
                Set.of("refund.created"), "key-inventory-replay");

        jdbcTemplate.update("""
                UPDATE webhook_endpoints
                SET signing_secret_key_version = 'current-v2',
                    previous_signing_secret_key_version = 'previous-v1'
                WHERE id = ?
                """, rotatedEndpoint.endpoint().getId());
        jdbcTemplate.update("""
                UPDATE webhook_endpoint_creation_records
                SET signing_secret_key_version = 'replay-v0'
                WHERE webhook_endpoint_id = ?
                """, replayEndpoint.endpoint().getId());

        Instant now = DatabaseInstant.now();
        assertThat(endpointRepository.findRequiredSecretKeyVersions(now))
                .containsExactlyInAnyOrder(
                        "current-v2", "previous-v1", "test-v1", "replay-v0");
        assertThat(endpointRepository.findRequiredSecretKeyVersions(now.plusSeconds(25 * 60 * 60)))
                .containsExactlyInAnyOrder("current-v2", "test-v1");
    }

    @Test
    void claimedSecretGenerationRemainsAvailableOnlyDuringRotationGrace() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("rotation-grace", "thaap_rotation_grace"));
        MerchantContext merchantContext = context(merchant, "rotation-grace");
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                merchantContext, "rotation-create", "https://merchant.example/rotation",
                Set.of("refund.created"), "rotation-create");
        UUID originalGeneration = created.endpoint().getSigningSecretGeneration();
        String originalPrefix = created.endpoint().getSigningSecretPrefix();

        WebhookEndpointService.EndpointSecretResult rotated = endpointService.rotateSecret(
                merchantContext, created.endpoint().getId(), "rotation-first");
        WebhookEndpoint stored = endpointRepository.findById(created.endpoint().getId()).orElseThrow();
        Instant expiresAt = stored.getPreviousSigningSecretExpiresAt();

        assertThat(stored.getPreviousSigningSecretGeneration()).isEqualTo(originalGeneration);
        assertThat(stored.getPreviousSigningSecretPrefix())
                .isEqualTo(originalPrefix);
        assertThat(endpointSecretService.loadForDelivery(
                merchant.getId(), stored.getId(), originalGeneration, expiresAt.minusNanos(1)))
                .isEqualTo(created.rawSigningSecret());
        assertThat(endpointSecretService.loadForDelivery(
                merchant.getId(), stored.getId(), stored.getSigningSecretGeneration(),
                expiresAt.plusSeconds(1)))
                .isEqualTo(rotated.rawSigningSecret());

        String payload = "{\"id\":\"" + UUID.randomUUID() + "\"}";
        WebhookSignature previousSignature = endpointSecretService.signForDelivery(
                merchant.getId(), stored.getId(), originalGeneration, payload,
                expiresAt.minusNanos(1), webhookSigner);
        assertThat(webhookSigner.verify(
                created.rawSigningSecret(), payload,
                previousSignature.timestampSeconds(), previousSignature.value())).isTrue();
        WebhookSignature currentSignature = endpointSecretService.signForDelivery(
                merchant.getId(), stored.getId(), stored.getSigningSecretGeneration(), payload,
                expiresAt.plusSeconds(1), webhookSigner);
        assertThat(webhookSigner.verify(
                rotated.rawSigningSecret(), payload,
                currentSignature.timestampSeconds(), currentSignature.value())).isTrue();

        assertThatThrownBy(() -> endpointSecretService.loadForDelivery(
                merchant.getId(), stored.getId(), originalGeneration, expiresAt))
                .isInstanceOf(WebhookSecretException.class)
                .satisfies(exception -> assertThat(
                        ((WebhookSecretException) exception).getErrorCode())
                        .isEqualTo(WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE));
        assertThatThrownBy(() -> endpointSecretService.signForDelivery(
                merchant.getId(), stored.getId(), originalGeneration, payload,
                expiresAt, webhookSigner))
                .isInstanceOf(WebhookStaleSecretGenerationException.class)
                .satisfies(exception -> assertThat(
                        ((WebhookSecretException) exception).getErrorCode())
                        .isEqualTo(WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE));
        assertThatThrownBy(() -> endpointService.rotateSecret(
                merchantContext, stored.getId(), "rotation-overlap"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> endpointService.create(
                merchantContext, "rotation-create",
                "https://merchant.example/rotation",
                Set.of("refund.created"), "stale-create-replay"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    private void createDeliveries(UUID merchantId, String eventType, UUID resourceId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> deliveryService.createDeliveriesInCurrentTransaction(merchantId, eventType, resourceId));
    }

    private MerchantContext context(Merchant merchant, String name) {
        return new MerchantContext(merchant.getId(), name, merchant.getApiKeyPrefix());
    }

    private WebhookEndpointService endpointService(SensitiveValueHasher hasher) {
        return new WebhookEndpointService(
                endpointRepository,
                creationRecordRepository,
                creationIdentityRepository,
                deliveryRepository,
                subscriptionStore,
                secretCipher,
                auditLogService,
                webhookMetrics,
                DatabaseInstant.tick(Clock.systemUTC()),
                hasher,
                transactionManager,
                Duration.ofMinutes(15),
                org.mockito.Mockito.mock(com.thaap.payments.risk.RiskOperationsEventProducer.class));
    }
}
