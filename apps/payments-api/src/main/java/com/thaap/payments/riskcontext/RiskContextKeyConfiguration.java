package com.thaap.payments.riskcontext;

import com.thaap.payments.security.SensitiveValueHasher;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class RiskContextKeyConfiguration {

    private final RiskContextRepository repository;
    private final SensitiveValueHasher hasher;
    private final RiskContextMetrics metrics;

    public RiskContextKeyConfiguration(RiskContextRepository repository, SensitiveValueHasher hasher,
            RiskContextMetrics metrics) {
        this.repository = repository;
        this.hasher = hasher;
        this.metrics = metrics;
    }

    @PostConstruct
    void validateRequiredKeys() {
        for (String keyVersion : repository.findRequiredIdentityKeyVersions()) {
            if (!hasher.canHashWith(keyVersion)) {
                metrics.recordReadinessFailure();
                throw new IllegalStateException("RiskContext identity key version is unavailable: " + keyVersion);
            }
        }
    }
}
