package com.thaap.payments.webhook;

import com.thaap.payments.security.SecretHasher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints", indexes = @Index(name = "idx_webhook_endpoints_merchant", columnList = "merchant_id"))
public class WebhookEndpoint {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private String signingSecretPrefix;

    @Column(nullable = false)
    private String signingSecretHash;

    @Column(nullable = false)
    private Instant createdAt;

    protected WebhookEndpoint() {
    }

    public WebhookEndpoint(UUID merchantId, String url) {
        this(merchantId, url, WebhookSigningSecret.generate());
    }

    public WebhookEndpoint(UUID merchantId, String url, String rawSigningSecret) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.url = url;
        this.active = true;
        this.signingSecretPrefix = SecretHasher.prefix(rawSigningSecret);
        this.signingSecretHash = SecretHasher.sha256(rawSigningSecret);
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public boolean isActive() {
        return active;
    }

    public String getSigningSecretPrefix() {
        return signingSecretPrefix;
    }

    public String getSigningSecretHash() {
        return signingSecretHash;
    }
}
