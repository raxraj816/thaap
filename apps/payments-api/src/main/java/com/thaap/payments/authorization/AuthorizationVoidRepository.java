package com.thaap.payments.authorization;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationVoidRepository extends JpaRepository<AuthorizationVoid, UUID> {

    Optional<AuthorizationVoid> findByAuthorizationIdAndMerchantId(UUID authorizationId, UUID merchantId);

    boolean existsByAuthorizationIdAndMerchantId(UUID authorizationId, UUID merchantId);
}
