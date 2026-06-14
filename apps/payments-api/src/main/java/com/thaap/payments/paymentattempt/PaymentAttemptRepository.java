package com.thaap.payments.paymentattempt;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Query("""
            SELECT COALESCE(MAX(attempt.attemptNumber), 0)
            FROM PaymentAttempt attempt
            WHERE attempt.merchantId = :merchantId
              AND attempt.paymentIntentId = :paymentIntentId
            """)
    int maxAttemptNumber(@Param("merchantId") UUID merchantId, @Param("paymentIntentId") UUID paymentIntentId);
}
