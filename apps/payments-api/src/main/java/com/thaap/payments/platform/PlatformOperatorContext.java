package com.thaap.payments.platform;

import java.util.UUID;

public record PlatformOperatorContext(UUID operatorId, String operatorCode,
        PlatformOperatorRole role, long lifecycleEpoch, UUID credentialId,
        String credentialPrefix) {
}
