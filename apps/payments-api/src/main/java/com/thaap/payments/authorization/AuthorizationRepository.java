package com.thaap.payments.authorization;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    Optional<Authorization> findByPaymentAttemptIdAndMerchantId(UUID paymentAttemptId, UUID merchantId);
}
