package com.thaap.payments.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface FraudLabelRepository extends JpaRepository<FraudLabel, UUID> {
    Optional<FraudLabel> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<FraudLabel> findByMerchantIdAndSourceReviewResolutionId(UUID merchantId, UUID resolutionId);
    Optional<FraudLabel> findByMerchantIdAndSourceChargebackId(UUID merchantId, UUID chargebackId);
    List<FraudLabel> findByMerchantIdOrderByCreatedAtAscIdAsc(UUID merchantId, Pageable pageable);

    @Query("""
            SELECT label FROM FraudLabel label
            WHERE label.merchantId = :merchantId
              AND label.paymentIntentId = :paymentIntentId
              AND NOT EXISTS (
                  SELECT successor.id FROM FraudLabel successor
                  WHERE successor.merchantId = label.merchantId
                    AND successor.supersedesLabelId = label.id
              )
            ORDER BY label.availableAt DESC, label.id DESC
            """)
    List<FraudLabel> findCurrentForPaymentIntent(
            @Param("merchantId") UUID merchantId,
            @Param("paymentIntentId") UUID paymentIntentId);

    @Query("""
            SELECT label FROM FraudLabel label
            WHERE label.merchantId = :merchantId
              AND (label.createdAt > :cursorCreatedAt
                   OR (label.createdAt = :cursorCreatedAt AND label.id > :cursorId))
            ORDER BY label.createdAt ASC, label.id ASC
            """)
    List<FraudLabel> listAfter(@Param("merchantId") UUID merchantId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId, Pageable pageable);
}
