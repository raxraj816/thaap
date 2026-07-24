package com.thaap.payments.postauth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostAuthorizationSignalRepository extends JpaRepository<PostAuthorizationSignal, UUID> {
    Optional<PostAuthorizationSignal> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<PostAuthorizationSignal> findByMerchantIdAndExternalEventKey(UUID merchantId, String externalEventKey);
}
