package com.thaap.payments.webhook;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT delivery
            FROM WebhookDelivery delivery
            WHERE delivery.id = :id
            """)
    Optional<WebhookDelivery> findByIdForUpdate(@Param("id") UUID id);

    List<WebhookDelivery> findByMerchantIdOrderByCreatedAtDescIdDesc(UUID merchantId, Pageable pageable);

    @Query("""
            SELECT delivery
            FROM WebhookDelivery delivery
            WHERE delivery.merchantId = :merchantId
              AND (
                  delivery.createdAt < :beforeCreatedAt
                  OR (
                      delivery.createdAt = :beforeCreatedAt
                      AND delivery.id < :beforeId
                  )
              )
            ORDER BY delivery.createdAt DESC, delivery.id DESC
            """)
    List<WebhookDelivery> findPageBefore(
            @Param("merchantId") UUID merchantId,
            @Param("beforeCreatedAt") java.time.Instant beforeCreatedAt,
            @Param("beforeId") UUID beforeId,
            Pageable pageable);

    @Query("""
            SELECT delivery.eventType AS eventType, COUNT(delivery) AS deliveryCount
            FROM WebhookDelivery delivery
            WHERE delivery.merchantId = :merchantId
              AND delivery.webhookEndpointId = :endpointId
              AND delivery.status IN :queuedStatuses
            GROUP BY delivery.eventType
            """)
    List<WebhookDeliveryCountByEventType> countQueuedByEventType(
            @Param("merchantId") UUID merchantId,
            @Param("endpointId") UUID endpointId,
            @Param("queuedStatuses") List<WebhookDeliveryStatus> queuedStatuses);

    @Query(value = """
            SELECT COUNT(*)
            FROM webhook_deliveries delivery
            JOIN webhook_endpoints endpoint
              ON endpoint.merchant_id = delivery.merchant_id
             AND endpoint.id = delivery.webhook_endpoint_id
            WHERE (
                delivery.status = 'DELIVERING'
                AND delivery.lease_expires_at <= :now
            )
            OR (
                endpoint.operational_status = 'ACTIVE'
                AND (
                    delivery.status = 'PENDING'
                    OR (
                        delivery.status = 'RETRY_SCHEDULED'
                        AND delivery.next_attempt_at <= :now
                    )
                )
            )
            """, nativeQuery = true)
    long countDue(@Param("now") java.time.Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WebhookDelivery delivery
            SET delivery.status = :exhaustedStatus,
                delivery.nextAttemptAt = NULL,
                delivery.lastErrorCode = :errorCode,
                delivery.claimToken = NULL,
                delivery.leaseExpiresAt = NULL
            WHERE delivery.merchantId = :merchantId
              AND delivery.webhookEndpointId = :endpointId
              AND delivery.status IN :queuedStatuses
            """)
    int exhaustQueuedForDisabledEndpoint(
            @Param("merchantId") UUID merchantId,
            @Param("endpointId") UUID endpointId,
            @Param("queuedStatuses") List<WebhookDeliveryStatus> queuedStatuses,
            @Param("exhaustedStatus") WebhookDeliveryStatus exhaustedStatus,
            @Param("errorCode") WebhookDeliveryErrorCode errorCode);
}
