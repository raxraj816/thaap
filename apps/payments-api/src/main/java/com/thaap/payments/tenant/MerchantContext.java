package com.thaap.payments.tenant;

import java.util.UUID;

public record MerchantContext(UUID merchantId, String merchantName, String apiKeyPrefix,
        TrafficOrigin trafficOrigin) {

    public MerchantContext(UUID merchantId, String merchantName, String apiKeyPrefix) {
        this(merchantId, merchantName, apiKeyPrefix, TrafficOrigin.MERCHANT_API);
    }
}
