package com.thaap.payments.risk;

import com.thaap.payments.webhook.WebhookEncryptedSecret;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_operator_credentials", uniqueConstraints = {
        @UniqueConstraint(name = "uk_risk_operator_credentials_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_risk_operator_credentials_operator",
                columnNames = {"merchant_id", "risk_operator_id"})
})
public class RiskOperatorCredential {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "risk_operator_id", nullable = false) private UUID riskOperatorId;
    @Column(name = "secret_prefix", nullable = false, length = 32) private String secretPrefix;
    @Column(name = "secret_sha256", nullable = false, length = 64) private String secretSha256;
    @Column(name = "secret_identity_hmac", nullable = false, length = 64) private String secretIdentityHmac;
    @Column(name = "secret_hmac_key_version", nullable = false, length = 32) private String secretHmacKeyVersion;
    @Column(name = "creation_identity_hmac", nullable = false, length = 64) private String creationIdentityHmac;
    @Column(name = "creation_request_hash", nullable = false, length = 64) private String creationRequestHash;
    @Column(name = "creation_hash_key_version", nullable = false, length = 32) private String creationHashKeyVersion;
    @Column(name = "replay_ciphertext") private String replayCiphertext;
    @Column(name = "replay_nonce", length = 64) private String replayNonce;
    @Column(name = "replay_key_version", length = 32) private String replayKeyVersion;
    @Column(name = "replay_expires_at") private Instant replayExpiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "disabled_at") private Instant disabledAt;

    protected RiskOperatorCredential() {
    }

    public RiskOperatorCredential(UUID merchantId, UUID riskOperatorId, String secretPrefix,
            String secretSha256, String secretIdentityHmac, String secretHmacKeyVersion,
            String creationIdentityHmac, String creationRequestHash, String creationHashKeyVersion,
            WebhookEncryptedSecret encryptedSecret, Instant now, Instant replayExpiresAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.riskOperatorId = riskOperatorId;
        this.secretPrefix = secretPrefix;
        this.secretSha256 = secretSha256;
        this.secretIdentityHmac = secretIdentityHmac;
        this.secretHmacKeyVersion = secretHmacKeyVersion;
        this.creationIdentityHmac = creationIdentityHmac;
        this.creationRequestHash = creationRequestHash;
        this.creationHashKeyVersion = creationHashKeyVersion;
        this.replayCiphertext = encryptedSecret.ciphertext();
        this.replayNonce = encryptedSecret.nonce();
        this.replayKeyVersion = encryptedSecret.keyVersion();
        this.replayExpiresAt = replayExpiresAt;
        this.createdAt = now;
    }

    public void disable(Instant now) {
        if (disabledAt == null) {
            this.disabledAt = now;
        }
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getRiskOperatorId() { return riskOperatorId; }
    public String getSecretPrefix() { return secretPrefix; }
    public String getCreationRequestHash() { return creationRequestHash; }
    public String getCreationHashKeyVersion() { return creationHashKeyVersion; }
    public Instant getReplayExpiresAt() { return replayExpiresAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public WebhookEncryptedSecret encryptedReplay() {
        return new WebhookEncryptedSecret(replayCiphertext, replayNonce, replayKeyVersion);
    }
}
