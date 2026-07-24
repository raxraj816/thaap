package com.thaap.payments.velocity;

import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.security.SensitiveValueHasher;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VelocityIdentityFactory {

    private static final String KEY_PREFIX = "thaap:velocity:v1:";
    private final SensitiveValueHasher hasher;

    public VelocityIdentityFactory(SensitiveValueHasher hasher) {
        this.hasher = hasher;
    }

    public VelocityIdentities create(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, RiskContext riskContext) {
        String merchantId = paymentIntent.getMerchantId().toString();
        String memberMaterial = merchantId + '\0' + paymentIntent.getId() + '\0'
                + paymentAttempt.getAttemptNumber();
        List<SensitiveValueHasher.VersionedDigest> members = hasher.hmacSha256Keyring(
                "velocity:member:logical-attempt:v1", memberMaterial);
        List<VersionedVelocityIdentities> versions = new ArrayList<>();
        for (SensitiveValueHasher.VersionedDigest member : members) {
            String keyVersion = member.keyVersion();
            Map<VelocityDimension, String> keys = new EnumMap<>(VelocityDimension.class);
            keys.put(VelocityDimension.CARD_ATTEMPTS_10M,
                    key(VelocityDimension.CARD_ATTEMPTS_10M, merchantId,
                            paymentMethod.getCardFingerprint(), keyVersion));
            keys.put(VelocityDimension.CUSTOMER_ATTEMPTS_1H,
                    key(VelocityDimension.CUSTOMER_ATTEMPTS_1H, merchantId,
                            paymentIntent.getCustomerId().toString(), keyVersion));
            keys.put(VelocityDimension.MERCHANT_ATTEMPTS_1M,
                    key(VelocityDimension.MERCHANT_ATTEMPTS_1M, merchantId, merchantId, keyVersion));
            if (riskContext != null) {
                String ipIdentity = riskContext.getIpIdentityHmac(keyVersion);
                if (ipIdentity != null) {
                    keys.put(VelocityDimension.IP_ATTEMPTS_10M,
                            key(VelocityDimension.IP_ATTEMPTS_10M, merchantId, ipIdentity, keyVersion));
                }
            }
            versions.add(new VersionedVelocityIdentities(
                    keyVersion, member.digest(), Map.copyOf(keys)));
        }
        return new VelocityIdentities(List.copyOf(versions), hasher.currentKeyVersion());
    }

    private String key(VelocityDimension dimension, String merchantId, String identity, String keyVersion) {
        return KEY_PREFIX + hasher.hmacSha256("velocity:key:" + dimension.name().toLowerCase() + ":v1",
                merchantId + '\0' + identity, keyVersion);
    }

    public record VelocityIdentities(List<VersionedVelocityIdentities> versions, String keyVersion) {
        public VelocityIdentities {
            versions = List.copyOf(versions);
            if (versions.isEmpty() || !versions.getFirst().keyVersion().equals(keyVersion)) {
                throw new IllegalArgumentException("Velocity identities must begin with the current key version");
            }
        }
    }

    public record VersionedVelocityIdentities(String keyVersion, String member,
            Map<VelocityDimension, String> redisKeys) {
        public VersionedVelocityIdentities {
            redisKeys = Map.copyOf(redisKeys);
        }
    }
}
