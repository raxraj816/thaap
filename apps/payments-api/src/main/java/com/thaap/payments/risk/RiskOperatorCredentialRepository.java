package com.thaap.payments.risk;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskOperatorCredentialRepository extends JpaRepository<RiskOperatorCredential, UUID> {
    Optional<RiskOperatorCredential> findByMerchantIdAndRiskOperatorId(UUID merchantId, UUID riskOperatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT credential FROM RiskOperatorCredential credential "
            + "WHERE credential.merchantId = :merchantId "
            + "AND credential.riskOperatorId = :riskOperatorId")
    Optional<RiskOperatorCredential> findByMerchantIdAndRiskOperatorIdForUpdate(
            @Param("merchantId") UUID merchantId,
            @Param("riskOperatorId") UUID riskOperatorId);

    List<RiskOperatorCredential> findBySecretIdentityHmacInAndDisabledAtIsNull(List<String> identityHashes);
    List<RiskOperatorCredential> findByMerchantIdAndCreationIdentityHmacIn(UUID merchantId,
            List<String> identityHashes);
}
