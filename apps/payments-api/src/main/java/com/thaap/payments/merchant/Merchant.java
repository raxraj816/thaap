package com.thaap.payments.merchant;

import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.time.DatabaseInstant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status;

    @Column(nullable = false)
    private String apiKeyPrefix;

    @Column(nullable = false, unique = true)
    private String apiKeyHash;

    @Column(nullable = false)
    private Instant createdAt;

    protected Merchant() {
    }

    public Merchant(String name, String rawApiKey) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.status = MerchantStatus.ACTIVE;
        this.apiKeyPrefix = SecretHasher.prefix(rawApiKey);
        this.apiKeyHash = SecretHasher.sha256(rawApiKey);
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MerchantStatus getStatus() {
        return status;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }
}
