package com.thaap.payments.fraud;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureSnapshotRepository extends JpaRepository<FeatureSnapshot, UUID> {

    Optional<FeatureSnapshot> findByFraudDecisionIdAndMerchantId(UUID fraudDecisionId, UUID merchantId);
}
