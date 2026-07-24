package com.thaap.payments.dispute;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeOffsetAllocationRepository extends JpaRepository<DisputeOffsetAllocation, UUID> {
    long countByMerchantIdAndPaymentIntentIdAndResult(UUID merchantId, UUID paymentIntentId,
            ChargebackStatus result);
}
