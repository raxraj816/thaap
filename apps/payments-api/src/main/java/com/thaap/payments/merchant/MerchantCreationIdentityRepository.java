package com.thaap.payments.merchant;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantCreationIdentityRepository
        extends JpaRepository<MerchantCreationIdentity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT identity
            FROM MerchantCreationIdentity identity
            WHERE identity.identityHash IN :identityHashes
            ORDER BY identity.identityHash
            """)
    List<MerchantCreationIdentity> findByIdentityHashInForUpdate(
            @Param("identityHashes") Collection<String> identityHashes);
}
