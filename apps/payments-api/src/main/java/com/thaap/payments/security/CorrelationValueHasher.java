package com.thaap.payments.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Produces stable pseudonyms used for fraud and customer correlation.
 *
 * <p>This key has a different lifecycle from replay/idempotency keys. Routine
 * replay-key rotation must not split historical PAN, email, or phone identity.
 * Changing this key therefore requires an explicit offline correlation re-key,
 * rather than a normal application configuration rollout.</p>
 */
@Component
public class CorrelationValueHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    public CorrelationValueHasher(
            @Value("${thaap.security.correlation-pepper}") String correlationPepper) {
        if (correlationPepper == null || correlationPepper.isBlank()) {
            throw new IllegalStateException(
                    "thaap.security.correlation-pepper must be configured");
        }
        this.keySpec = new SecretKeySpec(
                correlationPepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String hmacSha256(String domain, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            mac.update(domain.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    public String hmacSha256Lowercase(String domain, String value) {
        return hmacSha256(domain, value.toLowerCase(Locale.ROOT));
    }
}
