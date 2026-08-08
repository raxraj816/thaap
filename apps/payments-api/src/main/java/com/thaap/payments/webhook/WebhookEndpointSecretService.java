package com.thaap.payments.webhook;

import com.thaap.payments.time.DatabaseInstant;
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
        this(endpointRepository, secretCipher, DatabaseInstant.tick(Clock.systemUTC()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String loadForDelivery(UUID merchantId, UUID endpointId) {
        return loadForDelivery(merchantId, endpointId, null, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String loadForDelivery(UUID merchantId, UUID endpointId,
            UUID secretGeneration, Instant now) {
        WebhookEndpoint endpoint = loadActiveEndpointForUpdate(merchantId, endpointId);
        if (secretGeneration == null
                || secretGeneration.equals(endpoint.getSigningSecretGeneration())) {
            return loadCurrentSecret(endpoint, merchantId, endpointId);
        }
        if (secretGeneration.equals(endpoint.getPreviousSigningSecretGeneration())
                && endpoint.getPreviousSigningSecretExpiresAt() != null
                && now.isBefore(endpoint.getPreviousSigningSecretExpiresAt())) {
            return loadPreviousSecret(endpoint, merchantId, endpointId);
        }
        throw staleGeneration();
    }

    /**
     * Locks generation state through exact HMAC use. Rotation and disable wait
     * until the signature timestamp has been bound to the selected secret.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookSignature signForDelivery(UUID merchantId, UUID endpointId,
            UUID secretGeneration, String payload, Instant notBefore,
            WebhookSigner signer) {
        WebhookEndpoint endpoint = loadActiveEndpointForUpdate(merchantId, endpointId);
        Instant signatureTime = laterOf(notBefore, clock.instant());
        String rawSecret;
        if (secretGeneration == null
                || secretGeneration.equals(endpoint.getSigningSecretGeneration())) {
            rawSecret = loadCurrentSecret(endpoint, merchantId, endpointId);
        } else if (secretGeneration.equals(endpoint.getPreviousSigningSecretGeneration())
                && endpoint.getPreviousSigningSecretExpiresAt() != null
                && signatureTime.isBefore(endpoint.getPreviousSigningSecretExpiresAt())) {
            rawSecret = loadPreviousSecret(endpoint, merchantId, endpointId);
        } else {
            throw staleGeneration();
        }
        try {
            return signer.sign(rawSecret, payload, signatureTime);
        } catch (RuntimeException exception) {
            throw new WebhookSigningException(
                    "Webhook payload could not be signed", exception);
        }
    }

    private WebhookEndpoint loadActiveEndpointForUpdate(UUID merchantId, UUID endpointId) {
        WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantIdForUpdate(endpointId, merchantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Webhook endpoint lineage disappeared"));
        if (endpoint.getOperationalStatus() == WebhookEndpointStatus.DISABLED) {
            throw new WebhookSecretException("Webhook endpoint is disabled",
                    WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        }
        if (endpoint.getOperationalStatus() != WebhookEndpointStatus.ACTIVE) {
            throw new WebhookSecretException(
                    "Webhook endpoint signing secret is unavailable for delivery",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
        return endpoint;
    }

    private String loadCurrentSecret(
            WebhookEndpoint endpoint, UUID merchantId, UUID endpointId) {
        String rawSecret = secretCipher.decrypt(endpoint);
        if (!secretCipher.isCurrentKeyVersion(endpoint.getSigningSecretKeyVersion())) {
            WebhookEncryptedSecret reencrypted = secretCipher.encrypt(
                    rawSecret, merchantId, endpointId);
            endpoint.reencryptSecret(reencrypted);
        }
        return rawSecret;
    }

    private String loadPreviousSecret(
            WebhookEndpoint endpoint, UUID merchantId, UUID endpointId) {
        return secretCipher.decrypt(new WebhookEncryptedSecret(
                endpoint.getPreviousSigningSecretCiphertext(),
                endpoint.getPreviousSigningSecretNonce(),
                endpoint.getPreviousSigningSecretKeyVersion()), merchantId, endpointId);
    }

    private WebhookStaleSecretGenerationException staleGeneration() {
        return new WebhookStaleSecretGenerationException(
                "Webhook signing secret generation is unavailable or expired",
                WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
    }

    private Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
