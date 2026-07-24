package com.thaap.payments.incident;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardTestingIncidentRepository extends JpaRepository<CardTestingIncident, UUID> {
    Optional<CardTestingIncident> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select incident from CardTestingIncident incident where incident.id = :id and incident.merchantId = :merchantId")
    Optional<CardTestingIncident> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select incident from CardTestingIncident incident where incident.merchantId = :merchantId and incident.status = 'OPEN'")
    Optional<CardTestingIncident> findOpenByMerchantIdForUpdate(@Param("merchantId") UUID merchantId);

    @Query("select incident from CardTestingIncident incident where incident.merchantId = :merchantId "
            + "and incident.status = :status order by incident.createdAt desc, incident.id desc")
    List<CardTestingIncident> list(@Param("merchantId") UUID merchantId,
            @Param("status") CardTestingIncidentStatus status, Pageable pageable);

    @Query("select incident from CardTestingIncident incident where incident.merchantId = :merchantId "
            + "and incident.status = :status and (incident.createdAt < :cursorCreatedAt "
            + "or (incident.createdAt = :cursorCreatedAt and incident.id < :cursorId)) "
            + "order by incident.createdAt desc, incident.id desc")
    List<CardTestingIncident> listAfter(@Param("merchantId") UUID merchantId,
            @Param("status") CardTestingIncidentStatus status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt, @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
