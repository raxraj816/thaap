package com.thaap.payments.review;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRefundActionRepository extends JpaRepository<ReviewRefundAction, UUID> {
    Optional<ReviewRefundAction> findByMerchantIdAndReviewResolutionId(
            UUID merchantId, UUID reviewResolutionId);
}
