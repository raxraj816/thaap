package com.thaap.payments.incident;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardTestingIncidentResolutionRepository
        extends JpaRepository<CardTestingIncidentResolution, UUID> {
    Optional<CardTestingIncidentResolution> findByMerchantIdAndIncidentId(UUID merchantId, UUID incidentId);
}
