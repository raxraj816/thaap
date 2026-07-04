package com.thaap.payments.webhook;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointCreationRecordRepository
        extends JpaRepository<WebhookEndpointCreationRecord, UUID> {

    Optional<WebhookEndpointCreationRecord> findByMerchantIdAndIdempotencyKeyHash(
            UUID merchantId, String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT record
            FROM WebhookEndpointCreationRecord record
            WHERE record.merchantId = :merchantId
              AND record.idempotencyKeyHash = :idempotencyKeyHash
            """)
    Optional<WebhookEndpointCreationRecord> findByMerchantIdAndIdempotencyKeyHashForUpdate(
            @Param("merchantId") UUID merchantId,
            @Param("idempotencyKeyHash") String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT record
            FROM WebhookEndpointCreationRecord record
            WHERE record.id = :id
            """)
    Optional<WebhookEndpointCreationRecord> findByIdForUpdate(@Param("id") UUID id);
}
