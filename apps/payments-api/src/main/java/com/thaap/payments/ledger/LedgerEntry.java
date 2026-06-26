package com.thaap.payments.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ledger_entries_capture_account",
                        columnNames = {"merchant_id", "source_type", "source_id", "account"})
        },
        indexes = {
                @Index(name = "idx_ledger_entries_merchant_created", columnList = "merchant_id,created_at,id"),
                @Index(name = "idx_ledger_entries_merchant_group", columnList = "merchant_id,transaction_group_id"),
                @Index(name = "idx_ledger_entries_merchant_source", columnList = "merchant_id,source_type,source_id")
        })
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "transaction_group_id", nullable = false)
    private UUID transactionGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 64)
    private LedgerSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LedgerDirection direction;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(UUID merchantId, UUID transactionGroupId, LedgerSourceType sourceType, UUID sourceId,
            LedgerAccount account, LedgerDirection direction, long amount, String currency) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.transactionGroupId = transactionGroupId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getTransactionGroupId() {
        return transactionGroupId;
    }

    public LedgerSourceType getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
