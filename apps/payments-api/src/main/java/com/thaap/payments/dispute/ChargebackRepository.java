package com.thaap.payments.dispute;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChargebackRepository extends JpaRepository<Chargeback, UUID> {
    Optional<Chargeback> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<Chargeback> findByMerchantIdAndExternalEventKey(UUID merchantId, String externalEventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT chargeback FROM Chargeback chargeback WHERE chargeback.id = :id AND chargeback.merchantId = :merchantId")
    Optional<Chargeback> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);
}
