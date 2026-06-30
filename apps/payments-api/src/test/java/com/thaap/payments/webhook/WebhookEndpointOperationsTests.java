package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.audit.AuditLog;
import com.thaap.payments.audit.AuditLogRepository;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpStatus;
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
    private WebhookEndpointSubscriptionStore subscriptionStore;

    @Autowired
    private WebhookSecretCipher secretCipher;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void createFilterRotateDisableAndAuditStayTenantScopedAndSecretSafe() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_webhook_a"));
        Merchant otherMerchant = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_webhook_b"));
        MerchantContext merchantContext = context(merchant, "merchant-a");
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                merchantContext, "http://127.0.0.1:9191/thaap",
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
        endpointService.disable(merchantContext, stored.getId(), "request-disable");
        WebhookDelivery queued = deliveryRepository.findAll().getFirst();
        assertThat(queued.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(queued.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        createDeliveries(merchant.getId(), "refund.created", UUID.randomUUID());
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count() - terminalBefore)
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

    private void createDeliveries(UUID merchantId, String eventType, UUID resourceId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> deliveryService.createDeliveries(merchantId, eventType, resourceId));
    }

    private MerchantContext context(Merchant merchant, String name) {
        return new MerchantContext(merchant.getId(), name, merchant.getApiKeyPrefix());
    }
}
