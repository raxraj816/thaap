package com.thaap.payments.investigation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentInvestigationSavedViewRepository
        extends JpaRepository<PaymentInvestigationSavedView, UUID> {

    @Query("""
            SELECT view FROM PaymentInvestigationSavedView view
            WHERE view.merchantId = :merchantId
              AND view.lifecycleStatus = com.thaap.payments.workspace.SavedSearchLifecycleStatus.ACTIVE
              AND (view.ownerOperatorId = :operatorId
                   OR view.visibility = com.thaap.payments.workspace.SavedSearchVisibility.MERCHANT_SHARED)
            ORDER BY view.updatedAt DESC, view.id
            """)
    List<PaymentInvestigationSavedView> findVisible(@Param("merchantId") UUID merchantId,
            @Param("operatorId") UUID operatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT view FROM PaymentInvestigationSavedView view
            WHERE view.id = :id AND view.merchantId = :merchantId
            """)
    Optional<PaymentInvestigationSavedView> findByIdAndMerchantIdForUpdate(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);
}

