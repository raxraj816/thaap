package com.thaap.payments.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers", indexes = @Index(name = "idx_customers_merchant", columnList = "merchant_id"))
public class Customer {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String emailHash;

    @Column
    private String phoneHash;

    @Column(nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(UUID merchantId, String emailHash, String phoneHash) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.emailHash = emailHash;
        this.phoneHash = phoneHash;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
