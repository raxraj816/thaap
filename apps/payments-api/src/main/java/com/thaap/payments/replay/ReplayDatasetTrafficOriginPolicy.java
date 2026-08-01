package com.thaap.payments.replay;

import com.thaap.payments.tenant.TrafficOrigin;

public enum ReplayDatasetTrafficOriginPolicy {
    ALL,
    LIVE_ONLY,
    SYNTHETIC_ONLY;

    public boolean includes(TrafficOrigin origin) {
        return switch (this) {
            case ALL -> true;
            case LIVE_ONLY -> origin == TrafficOrigin.MERCHANT_API;
            case SYNTHETIC_ONLY -> origin == TrafficOrigin.SYNTHETIC_SCENARIO;
        };
    }
}
