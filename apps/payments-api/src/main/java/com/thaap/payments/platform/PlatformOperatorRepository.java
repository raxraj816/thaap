package com.thaap.payments.platform;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformOperatorRepository extends JpaRepository<PlatformOperator, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operator FROM PlatformOperator operator WHERE operator.id = :id")
    Optional<PlatformOperator> findByIdForUpdate(@Param("id") UUID id);
}
