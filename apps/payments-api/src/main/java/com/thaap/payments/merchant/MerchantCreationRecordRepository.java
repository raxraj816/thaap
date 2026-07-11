package com.thaap.payments.merchant;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantCreationRecordRepository
        extends JpaRepository<MerchantCreationRecord, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT record
            FROM MerchantCreationRecord record
            WHERE record.id = :id
            """)
    Optional<MerchantCreationRecord> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT DISTINCT record.derivationKeyVersion
            FROM MerchantCreationRecord record
            WHERE record.expiresAt >= :now
            """)
    List<String> findRequiredDerivationKeyVersions(@Param("now") Instant now);
}
