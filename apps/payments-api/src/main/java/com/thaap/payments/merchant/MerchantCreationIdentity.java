package com.thaap.payments.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_creation_identities",
        indexes = @Index(name = "idx_merchant_creation_identity_record",
                columnList = "merchant_creation_record_id"))
public class MerchantCreationIdentity {

    @Id
    @Column(name = "identity_hash", nullable = false, length = 64)
    private String identityHash;

    @Column(name = "key_version", nullable = false, length = 32)
    private String keyVersion;

    @Column(name = "merchant_creation_record_id", nullable = false)
    private UUID merchantCreationRecordId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantCreationIdentity() {
    }

    public MerchantCreationIdentity(String identityHash, String keyVersion,
            UUID merchantCreationRecordId, Instant createdAt) {
        this.identityHash = identityHash;
        this.keyVersion = keyVersion;
        this.merchantCreationRecordId = merchantCreationRecordId;
        this.createdAt = createdAt;
    }

    public String getIdentityHash() {
        return identityHash;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public UUID getMerchantCreationRecordId() {
        return merchantCreationRecordId;
    }
}
