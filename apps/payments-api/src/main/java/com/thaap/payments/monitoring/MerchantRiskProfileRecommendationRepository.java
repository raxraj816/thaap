package com.thaap.payments.monitoring;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRiskProfileRecommendationRepository
        extends JpaRepository<MerchantRiskProfileRecommendation, UUID> {
    Optional<MerchantRiskProfileRecommendation> findByMerchantIdAndMetricSnapshotId(
            UUID merchantId, UUID metricSnapshotId);
}
