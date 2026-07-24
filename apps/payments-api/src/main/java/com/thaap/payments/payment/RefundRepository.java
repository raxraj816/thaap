package com.thaap.payments.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdAndMerchantId(UUID id, UUID merchantId);

    boolean existsByPaymentIntentIdAndMerchantId(UUID paymentIntentId, UUID merchantId);

    Optional<Refund> findByPaymentIntentIdAndMerchantId(UUID paymentIntentId, UUID merchantId);

    Optional<Refund> findByMerchantIdAndSourceReviewResolutionId(
            UUID merchantId, UUID sourceReviewResolutionId);
}
