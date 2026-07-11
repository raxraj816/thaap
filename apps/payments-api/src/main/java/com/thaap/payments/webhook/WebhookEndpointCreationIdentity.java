package com.thaap.payments.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoint_creation_identities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_webhook_endpoint_creation_identity_merchant_hash",
                        columnNames = {"merchant_id", "identity_hash"}),
                @UniqueConstraint(
                        name = "uk_webhook_endpoint_creation_identity_record_version",
                        columnNames = {"creation_record_id", "key_version"})
        },
        indexes = @Index(name = "idx_webhook_endpoint_creation_identity_record",
                columnList = "creation_record_id"))
public class WebhookEndpointCreationIdentity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "identity_hash", nullable = false, length = 64)
    private String identityHash;

    @Column(name = "key_version", nullable = false, length = 32)
    private String keyVersion;

    @Column(name = "creation_record_id", nullable = false)
    private UUID creationRecordId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEndpointCreationIdentity() {
    }

    public WebhookEndpointCreationIdentity(UUID merchantId, String identityHash,
            String keyVersion, UUID creationRecordId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.identityHash = identityHash;
        this.keyVersion = keyVersion;
        this.creationRecordId = creationRecordId;
        this.createdAt = createdAt;
    }

    public String getIdentityHash() {
        return identityHash;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public UUID getCreationRecordId() {
        return creationRecordId;
    }
}
