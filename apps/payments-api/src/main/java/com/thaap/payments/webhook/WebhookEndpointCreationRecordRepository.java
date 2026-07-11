package com.thaap.payments.webhook;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.time.Instant;
import java.util.List;
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
            WHERE record.merchantId = :merchantId
              AND record.idempotencyKeyHash IN :idempotencyKeyHashes
            """)
    List<WebhookEndpointCreationRecord> findByMerchantIdAndIdempotencyKeyHashInForUpdate(
            @Param("merchantId") UUID merchantId,
            @Param("idempotencyKeyHashes") Collection<String> idempotencyKeyHashes);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT record
            FROM WebhookEndpointCreationRecord record
            WHERE record.id = :id
            """)
    Optional<WebhookEndpointCreationRecord> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT DISTINCT record.requestHashKeyVersion
            FROM WebhookEndpointCreationRecord record
            WHERE record.expiresAt >= :now
            """)
    List<String> findRequiredRequestHashKeyVersions(@Param("now") Instant now);
}
