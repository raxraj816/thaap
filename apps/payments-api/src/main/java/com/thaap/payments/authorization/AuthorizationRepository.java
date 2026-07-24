package com.thaap.payments.authorization;

import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    Optional<Authorization> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Authorization> findByPaymentAttemptIdAndMerchantId(UUID paymentAttemptId, UUID merchantId);

    @Query("""
            SELECT authorization
            FROM Authorization authorization
            JOIN PaymentAttempt attempt
              ON attempt.id = authorization.paymentAttemptId
             AND attempt.merchantId = authorization.merchantId
            WHERE authorization.merchantId = :merchantId
              AND attempt.paymentIntentId = :paymentIntentId
              AND attempt.status = :attemptStatus
              AND authorization.status = :authorizationStatus
            ORDER BY attempt.attemptNumber DESC
            """)
    List<Authorization> findApprovedAuthorizationsForPaymentIntent(@Param("merchantId") UUID merchantId,
            @Param("paymentIntentId") UUID paymentIntentId, @Param("attemptStatus") PaymentAttemptStatus attemptStatus,
            @Param("authorizationStatus") AuthorizationStatus authorizationStatus);
}
