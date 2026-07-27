package com.thaap.payments.workspace;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewSavedSearchRepository extends JpaRepository<ReviewSavedSearch, UUID> {

    @Query("""
            SELECT search FROM ReviewSavedSearch search
             WHERE search.merchantId = :merchantId
               AND search.lifecycleStatus = 'ACTIVE'
               AND (search.ownerOperatorId = :operatorId OR search.visibility = 'MERCHANT_SHARED')
             ORDER BY search.updatedAt DESC, search.id DESC
            """)
    List<ReviewSavedSearch> findVisible(@Param("merchantId") UUID merchantId,
            @Param("operatorId") UUID operatorId);

    Optional<ReviewSavedSearch> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<ReviewSavedSearch> findByMerchantIdAndOwnerOperatorIdAndName(
            UUID merchantId, UUID ownerOperatorId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT search FROM ReviewSavedSearch search WHERE search.id = :id AND search.merchantId = :merchantId")
    Optional<ReviewSavedSearch> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);
}
