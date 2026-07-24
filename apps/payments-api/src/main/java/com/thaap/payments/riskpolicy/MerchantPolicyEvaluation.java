package com.thaap.payments.riskpolicy;

import com.thaap.payments.fraud.FeatureValue;

public record MerchantPolicyEvaluation(
        MerchantPolicyThreshold threshold,
        FeatureValue featureValue,
        boolean matched) {
}
