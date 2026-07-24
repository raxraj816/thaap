package com.thaap.payments.riskpolicy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MerchantRiskConfiguration(
        UUID merchantId,
        MerchantRiskConfigurationStatus status,
        UUID profileVersionId,
        MerchantRiskTier riskTier,
        MerchantRiskProfileSource profileSource,
        UUID policyVersionId,
        String policyCode,
        int policyVersionNumber,
        List<MerchantPolicyThreshold> thresholds,
        Instant evaluatedAt) {

    public MerchantRiskConfiguration {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(riskTier, "riskTier");
        Objects.requireNonNull(profileSource, "profileSource");
        thresholds = List.copyOf(thresholds);
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    public static MerchantRiskConfiguration unavailable(UUID merchantId, Instant evaluatedAt) {
        return new MerchantRiskConfiguration(merchantId, MerchantRiskConfigurationStatus.UNAVAILABLE,
                null, MerchantRiskTier.STANDARD, MerchantRiskProfileSource.APPLICATION_DEFAULT,
                null, null, 0, List.of(), evaluatedAt);
    }
}
