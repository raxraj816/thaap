package com.thaap.payments.webhook;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    Optional<WebhookDeliveryAttempt> findByWebhookDeliveryIdAndAttemptNumber(UUID webhookDeliveryId,
            int attemptNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT attempt
            FROM WebhookDeliveryAttempt attempt
            WHERE attempt.webhookDeliveryId = :webhookDeliveryId
              AND attempt.attemptNumber = :attemptNumber
            """)
    Optional<WebhookDeliveryAttempt> findByWebhookDeliveryIdAndAttemptNumberForUpdate(
            @Param("webhookDeliveryId") UUID webhookDeliveryId,
            @Param("attemptNumber") int attemptNumber);

    List<WebhookDeliveryAttempt> findByMerchantIdAndWebhookDeliveryIdOrderByAttemptNumberDesc(
            UUID merchantId, UUID webhookDeliveryId, Pageable pageable);
}
