package com.thaap.payments.review;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

public interface ManualReviewCaseRepository extends JpaRepository<ManualReviewCase, UUID> {
    Optional<ManualReviewCase> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<ManualReviewCase> findByPaymentIntentIdAndMerchantIdAndStatus(
            UUID paymentIntentId, UUID merchantId, ManualReviewCaseStatus status);
    Optional<ManualReviewCase> findByPaymentIntentIdAndMerchantIdAndReviewStage(
            UUID paymentIntentId, UUID merchantId, ReviewStage reviewStage);

    @Query("""
            SELECT reviewCase.paymentIntentId AS paymentIntentId,
                   reviewCase.paymentAttemptId AS paymentAttemptId
            FROM ManualReviewCase reviewCase
            WHERE reviewCase.id = :id AND reviewCase.merchantId = :merchantId
            """)
    Optional<PaymentLineage> findPaymentLineageByIdAndMerchantId(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT reviewCase FROM ManualReviewCase reviewCase WHERE reviewCase.id = :id AND reviewCase.merchantId = :merchantId")
    Optional<ManualReviewCase> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT reviewCase FROM ManualReviewCase reviewCase WHERE reviewCase.paymentIntentId = :paymentIntentId AND reviewCase.merchantId = :merchantId")
    Optional<ManualReviewCase> findByPaymentIntentIdAndMerchantIdForUpdate(
            @Param("paymentIntentId") UUID paymentIntentId, @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT reviewCase FROM ManualReviewCase reviewCase
            WHERE reviewCase.paymentIntentId = :paymentIntentId
              AND reviewCase.merchantId = :merchantId
              AND reviewCase.status = :status
            """)
    Optional<ManualReviewCase> findForUpdate(@Param("paymentIntentId") UUID paymentIntentId,
            @Param("merchantId") UUID merchantId, @Param("status") ManualReviewCaseStatus status);

    @Query("""
            SELECT reviewCase FROM ManualReviewCase reviewCase
            WHERE reviewCase.merchantId = :merchantId
              AND reviewCase.status = :status
            ORDER BY reviewCase.createdAt ASC, reviewCase.id ASC
            """)
    List<ManualReviewCase> listFirst(@Param("merchantId") UUID merchantId,
            @Param("status") ManualReviewCaseStatus status, Pageable pageable);

    @Query("""
            SELECT reviewCase FROM ManualReviewCase reviewCase
            WHERE reviewCase.merchantId = :merchantId
              AND reviewCase.status = :status
              AND (reviewCase.createdAt > :cursorCreatedAt
                   OR (reviewCase.createdAt = :cursorCreatedAt AND reviewCase.id > :cursorId))
            ORDER BY reviewCase.createdAt ASC, reviewCase.id ASC
            """)
    List<ManualReviewCase> listAfter(@Param("merchantId") UUID merchantId,
            @Param("status") ManualReviewCaseStatus status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId, Pageable pageable);

    interface PaymentLineage {
        UUID getPaymentIntentId();

        UUID getPaymentAttemptId();
    }
}
