package com.thaap.payments.webhook;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    Optional<WebhookEndpoint> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT endpoint
            FROM WebhookEndpoint endpoint
            WHERE endpoint.id = :id
              AND endpoint.merchantId = :merchantId
            """)
    Optional<WebhookEndpoint> findByIdAndMerchantIdForUpdate(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT endpoint
            FROM WebhookEndpoint endpoint
            WHERE endpoint.id = :id
              AND endpoint.merchantId = :merchantId
            """)
    Optional<WebhookEndpoint> findByIdAndMerchantIdForShare(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);

    @Query(value = """
            SELECT DISTINCT required.key_version
            FROM (
                SELECT endpoint.signing_secret_key_version AS key_version
                FROM webhook_endpoints endpoint
                WHERE endpoint.operational_status = 'ACTIVE'

                UNION

                SELECT endpoint.previous_signing_secret_key_version AS key_version
                FROM webhook_endpoints endpoint
                WHERE endpoint.operational_status = 'ACTIVE'
                  AND endpoint.previous_signing_secret_expires_at > :now

                UNION

                SELECT creation.signing_secret_key_version AS key_version
                FROM webhook_endpoint_creation_records creation
                JOIN webhook_endpoints endpoint
                  ON endpoint.merchant_id = creation.merchant_id
                 AND endpoint.id = creation.webhook_endpoint_id
                WHERE creation.status = 'COMPLETED'
                  AND creation.expires_at >= :now
                  AND creation.signing_secret_generation = endpoint.signing_secret_generation
            ) required
            WHERE required.key_version IS NOT NULL
            """, nativeQuery = true)
    List<String> findRequiredSecretKeyVersions(@Param("now") java.time.Instant now);

    @Query(value = """
            SELECT CAST(endpoint.id AS VARCHAR)
            FROM webhook_endpoints endpoint
            JOIN webhook_endpoint_event_subscriptions subscription
              ON subscription.merchant_id = endpoint.merchant_id
             AND subscription.webhook_endpoint_id = endpoint.id
            WHERE endpoint.merchant_id = :merchantId
              AND endpoint.operational_status = 'ACTIVE'
              AND subscription.event_type = :eventType
            ORDER BY endpoint.created_at, endpoint.id
            """, nativeQuery = true)
    List<String> findActiveSubscribedEndpointIds(@Param("merchantId") UUID merchantId,
            @Param("eventType") String eventType);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT endpoint
            FROM WebhookEndpoint endpoint
            WHERE endpoint.merchantId = :merchantId
              AND endpoint.operationalStatus = com.thaap.payments.webhook.WebhookEndpointStatus.ACTIVE
              AND endpoint.id IN :endpointIds
            ORDER BY endpoint.createdAt, endpoint.id
            """)
    List<WebhookEndpoint> lockActiveEndpoints(
            @Param("merchantId") UUID merchantId,
            @Param("endpointIds") Collection<UUID> endpointIds);
}
