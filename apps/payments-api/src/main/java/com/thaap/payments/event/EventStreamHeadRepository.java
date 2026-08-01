package com.thaap.payments.event;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventStreamHeadRepository extends JpaRepository<EventStreamHead, EventStreamHeadId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT head
            FROM EventStreamHead head
            WHERE head.id.merchantId = :merchantId
              AND head.id.streamType = :streamType
              AND head.id.streamId = :streamId
            """)
    Optional<EventStreamHead> findForUpdate(@Param("merchantId") UUID merchantId,
            @Param("streamType") DomainStreamType streamType,
            @Param("streamId") UUID streamId);
}
