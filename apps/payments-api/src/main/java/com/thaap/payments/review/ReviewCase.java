package com.thaap.payments.review;

import com.thaap.payments.time.DatabaseInstant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_cases", indexes = @Index(name = "idx_review_cases_merchant", columnList = "merchant_id"))
public class ReviewCase {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReviewCase() {
    }

    public ReviewCase(UUID merchantId, String status) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.status = status;
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getStatus() {
        return status;
    }
}
