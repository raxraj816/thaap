package com.thaap.payments.riskcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskContextIdentityRotationTests {

    @Test
    void persistsCurrentAndRetainedPseudonymAliasesBeforeRawIdentitiesAreDiscarded() {
        SensitiveValueHasher hasher = new SensitiveValueHasher(
                "risk-context-new-pepper", "risk-v2",
                "risk-v1=cmlzay1jb250ZXh0LW9sZC1wZXBwZXI=");
        RiskContextRepository repository = mock(RiskContextRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RiskContextService service = new RiskContextService(repository, hasher,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC),
                mock(RiskContextMetrics.class));
        UUID merchantId = UUID.randomUUID();
        RiskContextRequest request = new RiskContextRequest(
                "203.0.113.21", "US", null, "device-21", "session-21", "US",
                CheckoutChannel.WEB, 10, 5, 100, CaptchaOutcome.PASSED,
                Instant.parse("2026-07-24T23:59:00Z"));

        RiskContext context = service.create(merchantId, UUID.randomUUID(), UUID.randomUUID(),
                request, TrafficOrigin.MERCHANT_API);

        assertThat(context.getIdentityKeyVersion()).isEqualTo("risk-v2");
        assertThat(context.getIpIdentityHmac("risk-v2")).isEqualTo(context.getIpIdentityHmac());
        assertThat(context.getIpIdentityHmac("risk-v1"))
                .isEqualTo(hasher.hmacSha256("risk-context:ip:v1",
                        merchantId + "\u0000" + request.ipAddress(), "risk-v1"));
        assertThat(context.getDeviceIdentityHmac("risk-v1")).isNotNull();
        assertThat(context.getSessionIdentityHmac("risk-v1")).isNotNull();
    }
}
