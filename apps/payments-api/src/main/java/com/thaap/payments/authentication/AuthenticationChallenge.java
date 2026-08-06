package com.thaap.payments.authentication;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Entity
@Table(name = "authentication_challenges",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_authentication_challenges_merchant_id_id",
                        columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_authentication_challenges_attempt",
                        columnNames = {"merchant_id", "payment_attempt_id"}),
                @UniqueConstraint(name = "uk_authentication_challenges_secret_sha256",
                        columnNames = "client_secret_sha256"),
                @UniqueConstraint(name = "uk_authentication_challenges_secret_hmac",
                        columnNames = "client_secret_identity_hmac")
        }, indexes = {
                @Index(name = "idx_authentication_challenges_merchant_status",
                        columnList = "merchant_id,status,expires_at,id"),
                @Index(name = "idx_authentication_challenges_intent",
                        columnList = "merchant_id,payment_intent_id")
        })
public class AuthenticationChallenge {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id", nullable = false) private UUID fraudDecisionId;
    @Column(name = "authorization_id") private UUID authorizationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private AuthenticationChallengeSource source;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private AuthenticationChallengeStatus status;
    @Column(name = "client_secret_prefix", nullable = false, length = 24) private String clientSecretPrefix;
    @Column(name = "client_secret_sha256", nullable = false, length = 64) private String clientSecretSha256;
    @Column(name = "client_secret_identity_hmac", nullable = false, length = 64)
    private String clientSecretIdentityHmac;
    @Column(name = "client_secret_key_version", nullable = false, length = 32)
    private String clientSecretKeyVersion;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "completion_attempted_at") private Instant completionAttemptedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Enumerated(EnumType.STRING) @Column(name = "result_code", length = 32)
    private AuthenticationResultCode resultCode;
    @Enumerated(EnumType.STRING) @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected AuthenticationChallenge() {
    }

    public AuthenticationChallenge(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, UUID authorizationId, AuthenticationChallengeSource source,
            String clientSecretPrefix, String clientSecretSha256, String clientSecretIdentityHmac,
            String clientSecretKeyVersion, Instant expiresAt, TrafficOrigin trafficOrigin, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.paymentAttemptId = paymentAttemptId;
        this.fraudDecisionId = fraudDecisionId;
        this.authorizationId = authorizationId;
        this.source = source;
        this.status = AuthenticationChallengeStatus.PENDING;
        this.clientSecretPrefix = clientSecretPrefix;
        this.clientSecretSha256 = clientSecretSha256;
        this.clientSecretIdentityHmac = clientSecretIdentityHmac;
        this.clientSecretKeyVersion = clientSecretKeyVersion;
        this.expiresAt = expiresAt;
        this.trafficOrigin = trafficOrigin;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public UUID getAuthorizationId() { return authorizationId; }
    public AuthenticationChallengeSource getSource() { return source; }
    public AuthenticationChallengeStatus getStatus() { return status; }
    public String getClientSecretPrefix() { return clientSecretPrefix; }
    public String getClientSecretSha256() { return clientSecretSha256; }
    public String getClientSecretIdentityHmac() { return clientSecretIdentityHmac; }
    public String getClientSecretKeyVersion() { return clientSecretKeyVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCompletionAttemptedAt() { return completionAttemptedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public AuthenticationResultCode getResultCode() { return resultCode; }
    public TrafficOrigin getTrafficOrigin() { return trafficOrigin; }
    public Instant getCreatedAt() { return createdAt; }

    public void attachAuthorization(UUID authorizationId) {
        if (source != AuthenticationChallengeSource.FRAUD_ENGINE || this.authorizationId != null) {
            throw conflict("cannot attach authorization");
        }
        this.authorizationId = authorizationId;
        this.updatedAt = Instant.now();
    }

    public void succeed(Instant now) {
        requirePending();
        completionAttemptedAt = now;
        completedAt = now;
        resultCode = AuthenticationResultCode.AUTHENTICATED;
        status = AuthenticationChallengeStatus.SUCCEEDED;
        updatedAt = now;
    }

    public void fail(Instant now) {
        requirePending();
        completionAttemptedAt = now;
        completedAt = now;
        resultCode = AuthenticationResultCode.AUTHENTICATION_FAILED;
        status = AuthenticationChallengeStatus.FAILED;
        updatedAt = now;
    }

    public void expire(Instant now) {
        requirePending();
        completionAttemptedAt = now;
        completedAt = now;
        resultCode = AuthenticationResultCode.CHALLENGE_EXPIRED;
        status = AuthenticationChallengeStatus.EXPIRED;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        requirePending();
        completedAt = now;
        resultCode = AuthenticationResultCode.PAYMENT_CANCELED;
        status = AuthenticationChallengeStatus.CANCELED;
        updatedAt = now;
    }

    private void requirePending() {
        if (status != AuthenticationChallengeStatus.PENDING) {
            throw conflict("is already terminal");
        }
    }

    private ResponseStatusException conflict(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.AUTHENTICATION_CHALLENGE_CONFLICT, "Authentication challenge " + message);
    }
}
