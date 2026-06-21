package com.thaap.payments.tenant;

import java.util.UUID;

public record MerchantContext(UUID merchantId, String merchantName, String apiKeyPrefix) {
}
