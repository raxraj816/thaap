package com.thaap.payments.eventpublication;

import com.thaap.payments.event.DomainStreamType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class TenantQualifiedStreamKey {

    private static final String VERSION = "v1";

    private TenantQualifiedStreamKey() {
    }

    public static byte[] encode(UUID merchantId, DomainStreamType streamType,
            UUID streamId) {
        if (merchantId == null || streamType == null || streamId == null) {
            throw new IllegalArgumentException(
                    "Tenant-qualified stream key fields are required");
        }
        String canonical = VERSION + ":" + merchantId + ":"
                + streamType.name() + ":" + streamId;
        return canonical.getBytes(StandardCharsets.US_ASCII);
    }
}
