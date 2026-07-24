package com.thaap.payments.risk;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskOperatorRepository extends JpaRepository<RiskOperator, UUID> {
    Optional<RiskOperator> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operator FROM RiskOperator operator WHERE operator.id = :id AND operator.merchantId = :merchantId")
    Optional<RiskOperator> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);
}
