package com.thaap.payments.authentication;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthenticationChallengeRepository extends JpaRepository<AuthenticationChallenge, UUID> {

    @Query("""
            SELECT challenge FROM AuthenticationChallenge challenge
            WHERE challenge.id = :id AND challenge.merchantId = :merchantId
            """)
    Optional<AuthenticationChallenge> findByIdAndMerchantId(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT challenge FROM AuthenticationChallenge challenge
            WHERE challenge.id = :id AND challenge.merchantId = :merchantId
            """)
    Optional<AuthenticationChallenge> findByIdAndMerchantIdForUpdate(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);

    Optional<AuthenticationChallenge> findByMerchantIdAndPaymentAttemptId(
            UUID merchantId, UUID paymentAttemptId);

    Optional<AuthenticationChallenge> findByMerchantIdAndPaymentIntentIdAndStatus(
            UUID merchantId, UUID paymentIntentId, AuthenticationChallengeStatus status);

    @Query("""
            SELECT DISTINCT challenge.clientSecretKeyVersion
            FROM AuthenticationChallenge challenge
            WHERE challenge.status = com.thaap.payments.authentication.AuthenticationChallengeStatus.PENDING
              AND challenge.expiresAt > :now
            """)
    List<String> findRequiredSecretKeyVersions(@Param("now") Instant now);

    @Query("""
            SELECT challenge.paymentIntentId AS paymentIntentId,
                   challenge.paymentAttemptId AS paymentAttemptId
            FROM AuthenticationChallenge challenge
            WHERE challenge.id = :id AND challenge.merchantId = :merchantId
            """)
    Optional<ChallengeLineage> findLineageByIdAndMerchantId(
            @Param("id") UUID id, @Param("merchantId") UUID merchantId);

    interface ChallengeLineage {
        UUID getPaymentIntentId();
        UUID getPaymentAttemptId();
    }
}
