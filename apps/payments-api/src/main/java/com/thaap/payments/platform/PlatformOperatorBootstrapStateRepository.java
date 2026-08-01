package com.thaap.payments.platform;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PlatformOperatorBootstrapStateRepository
        extends JpaRepository<PlatformOperatorBootstrapState, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM PlatformOperatorBootstrapState state WHERE state.singletonId = 1")
    Optional<PlatformOperatorBootstrapState> findForUpdate();
}
