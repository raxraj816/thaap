package com.thaap.payments.idempotency;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByMerchantIdAndKey(UUID merchantId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT record
            FROM IdempotencyRecord record
            WHERE record.merchantId = :merchantId
              AND record.key = :key
            """)
    Optional<IdempotencyRecord> findByMerchantIdAndKeyForUpdate(
            @Param("merchantId") UUID merchantId, @Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT record
            FROM IdempotencyRecord record
            WHERE record.id = :id
            """)
    Optional<IdempotencyRecord> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT DISTINCT record.requestHashKeyVersion
            FROM IdempotencyRecord record
            WHERE record.expiresAt >= :now
            """)
    List<String> findRequiredRequestHashKeyVersions(@Param("now") Instant now);
}
