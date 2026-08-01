package com.thaap.payments.platform;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface PlatformOperatorRequestRepository
        extends JpaRepository<PlatformOperatorRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT request FROM PlatformOperatorRequest request "
            + "WHERE request.requestIdentityHmac IN :identityHashes")
    List<PlatformOperatorRequest> findByRequestIdentityHmacInForUpdate(
            @Param("identityHashes") List<String> identityHashes);
}
