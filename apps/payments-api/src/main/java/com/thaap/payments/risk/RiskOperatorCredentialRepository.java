package com.thaap.payments.risk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskOperatorCredentialRepository extends JpaRepository<RiskOperatorCredential, UUID> {
    Optional<RiskOperatorCredential> findByMerchantIdAndRiskOperatorId(UUID merchantId, UUID riskOperatorId);
    List<RiskOperatorCredential> findBySecretIdentityHmacInAndDisabledAtIsNull(List<String> identityHashes);
    List<RiskOperatorCredential> findByMerchantIdAndCreationIdentityHmacIn(UUID merchantId,
            List<String> identityHashes);
}
