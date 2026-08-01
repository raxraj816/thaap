package com.thaap.payments.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainEventRepository extends JpaRepository<DomainEvent, UUID> {
    Optional<DomainEvent> findByMerchantIdAndId(UUID merchantId, UUID id);
    long countByMerchantId(UUID merchantId);
}
