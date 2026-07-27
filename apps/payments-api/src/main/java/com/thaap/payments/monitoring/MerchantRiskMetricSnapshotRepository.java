package com.thaap.payments.monitoring;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRiskMetricSnapshotRepository
        extends JpaRepository<MerchantRiskMetricSnapshot, UUID> {
    Optional<MerchantRiskMetricSnapshot> findByIdAndMerchantId(UUID id, UUID merchantId);
}
