package com.thaap.payments.paymentattempt;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT attempt FROM PaymentAttempt attempt WHERE attempt.id = :id AND attempt.merchantId = :merchantId")
    Optional<PaymentAttempt> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);

    @Query("""
            SELECT COALESCE(MAX(attempt.attemptNumber), 0)
            FROM PaymentAttempt attempt
            WHERE attempt.merchantId = :merchantId
              AND attempt.paymentIntentId = :paymentIntentId
            """)
    int maxAttemptNumber(@Param("merchantId") UUID merchantId, @Param("paymentIntentId") UUID paymentIntentId);
}
