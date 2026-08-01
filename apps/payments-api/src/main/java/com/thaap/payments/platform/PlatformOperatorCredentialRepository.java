package com.thaap.payments.platform;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformOperatorCredentialRepository
        extends JpaRepository<PlatformOperatorCredential, UUID> {

    List<PlatformOperatorCredential> findBySecretIdentityHmacIn(List<String> identityHashes);

    Optional<PlatformOperatorCredential> findByPlatformOperatorId(UUID operatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT credential FROM PlatformOperatorCredential credential WHERE credential.id = :id")
    Optional<PlatformOperatorCredential> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT credential FROM PlatformOperatorCredential credential "
            + "WHERE credential.platformOperatorId = :operatorId")
    Optional<PlatformOperatorCredential> findByPlatformOperatorIdForUpdate(
            @Param("operatorId") UUID operatorId);
}
