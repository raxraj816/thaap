package com.thaap.payments.merchant;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByApiKeyHash(String apiKeyHash);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select merchant from Merchant merchant where merchant.id = :id")
    Optional<Merchant> findByIdForRiskControlRead(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select merchant from Merchant merchant where merchant.id = :id")
    Optional<Merchant> findByIdForMoneyMutation(@Param("id") UUID id);
}
