package com.thaap.payments.eventpublication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.event.DomainStreamType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantQualifiedStreamKeyTests {

    @Test
    void keyIsVersionedTenantQualifiedAndRepresentationStable() {
        UUID merchantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID streamId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        byte[] key = TenantQualifiedStreamKey.encode(merchantId,
                DomainStreamType.PAYMENT, streamId);

        assertThat(new String(key, StandardCharsets.US_ASCII)).isEqualTo(
                "v1:00000000-0000-0000-0000-000000000001:PAYMENT:"
                        + "00000000-0000-0000-0000-000000000002");
        assertThat(TenantQualifiedStreamKey.encode(merchantId,
                DomainStreamType.RISK_CONTROL, streamId)).isNotEqualTo(key);
        assertThat(TenantQualifiedStreamKey.encode(UUID.randomUUID(),
                DomainStreamType.PAYMENT, streamId)).isNotEqualTo(key);
    }

    @Test
    void missingKeyPartitionIsRejected() {
        assertThatThrownBy(() -> TenantQualifiedStreamKey.encode(null,
                DomainStreamType.PAYMENT, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
