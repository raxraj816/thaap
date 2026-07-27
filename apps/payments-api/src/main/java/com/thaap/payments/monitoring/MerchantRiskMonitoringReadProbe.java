package com.thaap.payments.monitoring;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MerchantRiskMonitoringReadProbe {

    public void afterAuthorizationDenominator(UUID merchantId,
            MerchantRiskMetricCode metricCode) {
        // Deterministic test seam for proving one repeatable-read cohort.
    }
}
