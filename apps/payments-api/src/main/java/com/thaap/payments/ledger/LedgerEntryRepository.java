package com.thaap.payments.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByMerchantIdOrderByCreatedAtDescIdDesc(UUID merchantId, Pageable pageable);

    List<LedgerEntry> findByMerchantIdAndSourceTypeAndSourceId(UUID merchantId, LedgerSourceType sourceType,
            UUID sourceId);
}
