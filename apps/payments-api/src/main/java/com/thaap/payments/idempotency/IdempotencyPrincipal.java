package com.thaap.payments.idempotency;

import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import java.util.Objects;
import java.util.UUID;

record IdempotencyPrincipal(UUID merchantId, IdempotencyPrincipalType type,
        UUID principalId, String credentialPrefix) {

    IdempotencyPrincipal {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(principalId, "principalId");
        if (credentialPrefix == null || credentialPrefix.isBlank() || credentialPrefix.length() > 32) {
            throw new IllegalArgumentException("Idempotency credential prefix must contain 1 to 32 characters");
        }
    }

    static IdempotencyPrincipal merchant(MerchantContext context) {
        return new IdempotencyPrincipal(context.merchantId(), IdempotencyPrincipalType.MERCHANT_API,
                context.merchantId(), context.apiKeyPrefix());
    }

    static IdempotencyPrincipal riskOperator(RiskOperatorContext context) {
        return new IdempotencyPrincipal(context.merchantId(), IdempotencyPrincipalType.RISK_OPERATOR,
                context.operatorId(), context.credentialPrefix());
    }
}
