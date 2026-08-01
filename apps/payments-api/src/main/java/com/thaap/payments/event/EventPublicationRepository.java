package com.thaap.payments.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventPublicationRepository extends JpaRepository<EventPublication, UUID> {
    Optional<EventPublication> findByMerchantIdAndDomainEventId(UUID merchantId, UUID domainEventId);
    long countByMerchantIdAndLifecycleStatus(UUID merchantId, EventPublicationStatus lifecycleStatus);
}
