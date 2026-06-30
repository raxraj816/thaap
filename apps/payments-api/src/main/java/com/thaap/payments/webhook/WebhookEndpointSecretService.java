package com.thaap.payments.webhook;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointSecretService {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookSecretCipher secretCipher;

    public WebhookEndpointSecretService(WebhookEndpointRepository endpointRepository,
            WebhookSecretCipher secretCipher) {
        this.endpointRepository = endpointRepository;
        this.secretCipher = secretCipher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String loadForDelivery(UUID merchantId, UUID endpointId) {
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
        String rawSecret = secretCipher.decrypt(endpoint);
        if (!secretCipher.isCurrentKeyVersion(endpoint.getSigningSecretKeyVersion())) {
            WebhookEncryptedSecret reencrypted = secretCipher.encrypt(rawSecret, merchantId, endpointId);
            endpoint.reencryptSecret(reencrypted);
        }
        return rawSecret;
    }
}
