package com.thaap.payments.webhook;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointCreationIdentityRepository
        extends JpaRepository<WebhookEndpointCreationIdentity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT identity
            FROM WebhookEndpointCreationIdentity identity
            WHERE identity.merchantId = :merchantId
              AND identity.identityHash IN :identityHashes
            ORDER BY identity.identityHash
            """)
    List<WebhookEndpointCreationIdentity> findByMerchantIdAndIdentityHashInForUpdate(
            @Param("merchantId") UUID merchantId,
            @Param("identityHashes") Collection<String> identityHashes);
}
