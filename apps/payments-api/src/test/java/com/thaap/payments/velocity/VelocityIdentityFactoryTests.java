package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.security.SensitiveValueHasher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VelocityIdentityFactoryTests {

    private final SensitiveValueHasher hasher = new SensitiveValueHasher(
            "velocity-fraud-list-identity-pepper", "test-v1", "");
    private final VelocityIdentityFactory factory = new VelocityIdentityFactory(hasher);

    @Test
    void derivesStablePurposeSeparatedLowercaseDigestsWithoutRawIdentityMaterial() {
        UUID merchantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID customerId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        PaymentIntent intent = new PaymentIntent(merchantId, customerId, 1200L, "USD", CaptureMethod.AUTOMATIC);
        PaymentMethod method = new PaymentMethod(merchantId, customerId, "pm_token_canary",
                "card-fingerprint-canary", "424242", "4242", CardFunding.CREDIT, "US");
        PaymentAttempt attempt = new PaymentAttempt(merchantId, intent.getId(), method.getId(), "idem-canary", 1);
        RiskContext context = mock(RiskContext.class);
        when(context.getIpIdentityHmac()).thenReturn("ip-hmac-canary");
        when(context.getIpIdentityHmac("test-v1")).thenReturn("ip-hmac-canary");

        VelocityIdentityFactory.VelocityIdentities first = factory.create(intent, attempt, method, context);
        VelocityIdentityFactory.VelocityIdentities replay = factory.create(intent, attempt, method, context);

        assertThat(first).isEqualTo(replay);
        assertThat(first.keyVersion()).isEqualTo("test-v1");
        assertThat(first.versions()).hasSize(1);
        VelocityIdentityFactory.VersionedVelocityIdentities current = first.versions().getFirst();
        assertThat(current.member()).matches("[0-9a-f]{64}");
        assertThat(current.redisKeys()).hasSize(4);
        assertThat(current.redisKeys().values())
                .allMatch(key -> key.matches("thaap:velocity:v1:[0-9a-f]{64}"));
        assertThat(current.redisKeys().values()).doesNotHaveDuplicates();
        String rendered = first.toString();
        assertThat(rendered).doesNotContain("card-fingerprint-canary", "ip-hmac-canary",
                customerId.toString(), merchantId.toString(), "idem-canary", "pm_token_canary");
    }

    @Test
    void sameIdentityAcrossMerchantsCannotShareKeysOrMembers() {
        UUID customerId = UUID.randomUUID();
        VelocityIdentityFactory.VelocityIdentities left = identities(UUID.randomUUID(), customerId);
        VelocityIdentityFactory.VelocityIdentities right = identities(UUID.randomUUID(), customerId);

        assertThat(left.versions().getFirst().member()).isNotEqualTo(right.versions().getFirst().member());
        for (Map.Entry<VelocityDimension, String> entry :
                left.versions().getFirst().redisKeys().entrySet()) {
            assertThat(entry.getValue())
                    .isNotEqualTo(right.versions().getFirst().redisKeys().get(entry.getKey()));
        }
    }

    @Test
    void emitsCurrentAndRetainedIdentitiesForRollingVelocityWindows() {
        SensitiveValueHasher rollingHasher = new SensitiveValueHasher(
                "velocity-current-pepper", "test-v2",
                "test-v1=dmVsb2NpdHktcHJldmlvdXMtcGVwcGVy");
        VelocityIdentityFactory rollingFactory = new VelocityIdentityFactory(rollingHasher);
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(
                merchantId, customerId, 1200L, "USD", CaptureMethod.AUTOMATIC);
        PaymentMethod method = new PaymentMethod(merchantId, customerId, "token", "fingerprint",
                "424242", "4242", CardFunding.CREDIT, "US");
        PaymentAttempt attempt = new PaymentAttempt(merchantId, intent.getId(), method.getId(), "idem", 1);
        RiskContext context = mock(RiskContext.class);
        when(context.getIpIdentityHmac("test-v2")).thenReturn("current-ip-hmac");
        when(context.getIpIdentityHmac("test-v1")).thenReturn("previous-ip-hmac");

        VelocityIdentityFactory.VelocityIdentities identities = rollingFactory.create(
                intent, attempt, method, context);

        assertThat(identities.keyVersion()).isEqualTo("test-v2");
        assertThat(identities.versions()).extracting(
                VelocityIdentityFactory.VersionedVelocityIdentities::keyVersion)
                .containsExactly("test-v2", "test-v1");
        assertThat(identities.versions()).allMatch(version -> version.redisKeys().size() == 4);
        assertThat(identities.versions().get(0).member())
                .isNotEqualTo(identities.versions().get(1).member());
        for (VelocityDimension dimension : VelocityDimension.values()) {
            assertThat(identities.versions().get(0).redisKeys().get(dimension))
                    .isNotEqualTo(identities.versions().get(1).redisKeys().get(dimension));
        }
    }

    private VelocityIdentityFactory.VelocityIdentities identities(UUID merchantId, UUID customerId) {
        PaymentIntent intent = new PaymentIntent(merchantId, customerId, 1200L, "USD", CaptureMethod.AUTOMATIC);
        PaymentMethod method = new PaymentMethod(merchantId, customerId, "token", "shared-fingerprint",
                "424242", "4242", CardFunding.CREDIT, "US");
        PaymentAttempt attempt = new PaymentAttempt(merchantId, intent.getId(), method.getId(), "idem", 1);
        return factory.create(intent, attempt, method, null);
    }
}
