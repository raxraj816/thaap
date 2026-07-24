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
              AND record.principalType = :principalType
              AND record.principalId = :principalId
              AND record.principalCredentialPrefix = :credentialPrefix
              AND record.key = :key
            """)
    Optional<IdempotencyRecord> findByPrincipalAndKeyForUpdate(
            @Param("merchantId") UUID merchantId,
            @Param("principalType") IdempotencyPrincipalType principalType,
            @Param("principalId") UUID principalId,
            @Param("credentialPrefix") String credentialPrefix,
            @Param("key") String key);

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

    @Query("""
            SELECT record.responseBody
            FROM IdempotencyRecord record
            WHERE record.status = com.thaap.payments.idempotency.IdempotencyStatus.COMPLETED
              AND record.expiresAt > :now
              AND record.responseBody LIKE '%authentication-replay-v1%'
            """)
    List<String> findActiveProtectedAuthenticationReplays(@Param("now") Instant now);
}
