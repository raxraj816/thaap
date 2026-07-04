package com.thaap.payments.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointSecretService {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookSecretCipher secretCipher;
    private final Clock clock;

    @Autowired
    public WebhookEndpointSecretService(WebhookEndpointRepository endpointRepository,
            WebhookSecretCipher secretCipher, Clock clock) {
        this.endpointRepository = endpointRepository;
        this.secretCipher = secretCipher;
        this.clock = clock;
    }

    WebhookEndpointSecretService(WebhookEndpointRepository endpointRepository,
            WebhookSecretCipher secretCipher) {
        this(endpointRepository, secretCipher, Clock.systemUTC());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String loadForDelivery(UUID merchantId, UUID endpointId) {
        return loadForDelivery(merchantId, endpointId, null, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String loadForDelivery(UUID merchantId, UUID endpointId, UUID secretGeneration, Instant now) {
        WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantIdForUpdate(endpointId, merchantId)
                .orElseThrow(() -> new IllegalStateException("Webhook endpoint lineage disappeared"));
        if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
            throw new WebhookSecretException("Webhook endpoint is disabled",
                    WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        }
        if (endpoint.getOperationalStatus() != WebhookEndpointStatus.ACTIVE) {
            throw new WebhookSecretException("Webhook endpoint signing secret is unavailable for delivery",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
        if (secretGeneration == null || secretGeneration.equals(endpoint.getSigningSecretGeneration())) {
            String rawSecret = secretCipher.decrypt(endpoint);
            if (!secretCipher.isCurrentKeyVersion(endpoint.getSigningSecretKeyVersion())) {
                WebhookEncryptedSecret reencrypted = secretCipher.encrypt(rawSecret, merchantId, endpointId);
                endpoint.reencryptSecret(reencrypted);
            }
            return rawSecret;
        }
        if (secretGeneration.equals(endpoint.getPreviousSigningSecretGeneration())
                && endpoint.getPreviousSigningSecretExpiresAt() != null
                && now.isBefore(endpoint.getPreviousSigningSecretExpiresAt())) {
            return secretCipher.decrypt(new WebhookEncryptedSecret(
                    endpoint.getPreviousSigningSecretCiphertext(),
                    endpoint.getPreviousSigningSecretNonce(),
                    endpoint.getPreviousSigningSecretKeyVersion()), merchantId, endpointId);
        }
        throw new WebhookSecretException(
                "Webhook signing secret generation is unavailable or expired",
                WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookSecretUseWindow authorizeGenerationForUse(
            UUID merchantId, UUID endpointId, UUID secretGeneration) {
        WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantIdForUpdate(endpointId, merchantId)
                .orElseThrow(() -> new IllegalStateException("Webhook endpoint lineage disappeared"));
        if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
            throw new WebhookSecretException("Webhook endpoint is disabled",
                    WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        }
        if (endpoint.getOperationalStatus() != WebhookEndpointStatus.ACTIVE) {
            throw new WebhookSecretException("Webhook endpoint signing secret is unavailable for delivery",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
        if (secretGeneration == null || secretGeneration.equals(endpoint.getSigningSecretGeneration())) {
            return WebhookSecretUseWindow.unbounded();
        }
        if (secretGeneration.equals(endpoint.getPreviousSigningSecretGeneration())
                && endpoint.getPreviousSigningSecretExpiresAt() != null
                && clock.instant().isBefore(endpoint.getPreviousSigningSecretExpiresAt())) {
            return WebhookSecretUseWindow.bounded(
                    endpoint.getPreviousSigningSecretExpiresAt());
        }
        throw new WebhookSecretException(
                "Webhook signing secret generation is unavailable or expired",
                WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireGenerationUsableAt(
            UUID merchantId, UUID endpointId, UUID secretGeneration, Instant signatureTime) {
        WebhookSecretUseWindow useWindow = authorizeGenerationForUse(
                merchantId, endpointId, secretGeneration);
        useWindow.requireUsableAt(signatureTime);
    }
}
