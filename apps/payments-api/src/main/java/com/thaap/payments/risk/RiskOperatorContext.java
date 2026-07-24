package com.thaap.payments.risk;

import java.util.UUID;

public record RiskOperatorContext(UUID merchantId, UUID operatorId, String operatorCode,
        RiskOperatorRole role, String credentialPrefix) {
}
