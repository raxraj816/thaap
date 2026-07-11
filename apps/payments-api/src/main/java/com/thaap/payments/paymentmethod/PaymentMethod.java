package com.thaap.payments.paymentmethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_methods", indexes = {
        @Index(name = "idx_payment_methods_merchant", columnList = "merchant_id"),
        @Index(name = "idx_payment_methods_customer", columnList = "customer_id")
})
public class PaymentMethod {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private String cardFingerprint;

    @Column(nullable = false, length = 6)
    private String cardBin;

    @Column(nullable = false, length = 4)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardFunding cardFunding;

    @Column(nullable = false, length = 2)
    private String cardCountry;

    @Column(nullable = false)
    private Instant createdAt;

    protected PaymentMethod() {
    }

    public PaymentMethod(UUID merchantId, UUID customerId, String token, String cardFingerprint, String cardBin,
            String cardLast4, CardFunding cardFunding, String cardCountry) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.type = "CARD";
        this.token = token;
        this.cardFingerprint = cardFingerprint;
        this.cardBin = cardBin;
        this.cardLast4 = cardLast4;
        this.cardFunding = cardFunding;
        this.cardCountry = cardCountry;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getType() {
        return type;
    }

    public String getToken() {
        return token;
    }

    public String getCardFingerprint() {
        return cardFingerprint;
    }

    public String getCardBin() {
        return cardBin;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public CardFunding getCardFunding() {
        return cardFunding;
    }

    public String getCardCountry() {
        return cardCountry;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
