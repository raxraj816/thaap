package com.thaap.payments.authorization;

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

@Entity
@Table(name = "authorizations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_authorizations_merchant_id_id", columnNames = {"merchant_id", "id"}),
                @UniqueConstraint(name = "uk_authorizations_merchant_id_id_attempt",
                        columnNames = {"merchant_id", "id", "payment_attempt_id"}),
                @UniqueConstraint(name = "uk_authorizations_attempt",
                        columnNames = {"merchant_id", "payment_attempt_id"}),
                @UniqueConstraint(name = "uk_authorizations_processor_reference",
                        columnNames = {"processor", "processor_reference_id"})
        },
        indexes = {
                @Index(name = "idx_authorizations_merchant", columnList = "merchant_id"),
                @Index(name = "idx_authorizations_attempt", columnList = "payment_attempt_id"),
                @Index(name = "idx_authorizations_merchant_created", columnList = "merchant_id,created_at,id")
        })
public class Authorization {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(nullable = false, length = 64)
    private String processor;

    @Column(name = "processor_reference_id", nullable = false)
    private String processorReferenceId;

    @Column(name = "processor_response_code", nullable = false, length = 64)
    private String processorResponseCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_status", nullable = false, length = 64)
    private NetworkStatus networkStatus;

    @Column(name = "requested_amount", nullable = false)
    private long requestedAmount;

    @Column(name = "authorized_amount", nullable = false)
    private long authorizedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuthorizationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cvc_check", nullable = false, length = 64)
    private VerificationResult cvcCheck;

    @Enumerated(EnumType.STRING)
    @Column(name = "avs_line1_check", nullable = false, length = 64)
    private VerificationResult avsLine1Check;

    @Enumerated(EnumType.STRING)
    @Column(name = "avs_postal_code_check", nullable = false, length = 64)
    private VerificationResult avsPostalCodeCheck;

    @Enumerated(EnumType.STRING)
    @Column(name = "three_ds_result", nullable = false, length = 64)
    private ThreeDsResult threeDsResult;

    @Column(name = "simulator_outcome_code", nullable = false, length = 64)
    private String simulatorOutcomeCode;

    @Column(nullable = false)
    private Instant createdAt;

    protected Authorization() {
    }

    public Authorization(UUID merchantId, UUID paymentAttemptId, String processor, String processorReferenceId,
            String processorResponseCode, NetworkStatus networkStatus, long requestedAmount, long authorizedAmount,
            String currency, AuthorizationStatus status, VerificationResult cvcCheck,
            VerificationResult avsLine1Check, VerificationResult avsPostalCodeCheck, ThreeDsResult threeDsResult,
            String simulatorOutcomeCode) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentAttemptId = paymentAttemptId;
        this.processor = processor;
        this.processorReferenceId = processorReferenceId;
        this.processorResponseCode = processorResponseCode;
        this.networkStatus = networkStatus;
        this.requestedAmount = requestedAmount;
        this.authorizedAmount = authorizedAmount;
        this.currency = currency;
        this.status = status;
        this.cvcCheck = cvcCheck;
        this.avsLine1Check = avsLine1Check;
        this.avsPostalCodeCheck = avsPostalCodeCheck;
        this.threeDsResult = threeDsResult;
        this.simulatorOutcomeCode = simulatorOutcomeCode;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public String getProcessor() {
        return processor;
    }

    public String getProcessorReferenceId() {
        return processorReferenceId;
    }

    public String getProcessorResponseCode() {
        return processorResponseCode;
    }

    public NetworkStatus getNetworkStatus() {
        return networkStatus;
    }

    public long getAuthorizedAmount() {
        return authorizedAmount;
    }

    public long getRequestedAmount() {
        return requestedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public VerificationResult getCvcCheck() {
        return cvcCheck;
    }

    public VerificationResult getAvsLine1Check() {
        return avsLine1Check;
    }

    public VerificationResult getAvsPostalCodeCheck() {
        return avsPostalCodeCheck;
    }

    public ThreeDsResult getThreeDsResult() {
        return threeDsResult;
    }

    public String getSimulatorOutcomeCode() {
        return simulatorOutcomeCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void approveAfterAuthentication(String responseCode, long approvedAmount,
            ThreeDsResult authenticationResult) {
        if (status != AuthorizationStatus.REQUIRES_ACTION
                || threeDsResult != ThreeDsResult.REQUIRED) {
            throw new IllegalStateException("Authorization is not awaiting authentication");
        }
        if (approvedAmount != requestedAmount
                || authenticationResult != ThreeDsResult.AUTHENTICATED) {
            throw new IllegalArgumentException("Authenticated authorization result is inconsistent");
        }
        processorResponseCode = responseCode;
        networkStatus = NetworkStatus.APPROVED;
        authorizedAmount = approvedAmount;
        status = AuthorizationStatus.APPROVED;
        threeDsResult = authenticationResult;
        simulatorOutcomeCode = responseCode;
    }
}
