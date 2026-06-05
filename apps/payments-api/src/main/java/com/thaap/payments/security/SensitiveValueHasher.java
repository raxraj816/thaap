package com.thaap.payments.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SensitiveValueHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    public SensitiveValueHasher(@Value("${thaap.security.hash-pepper}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("thaap.security.hash-pepper must be configured");
        }
        this.keySpec = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String hmacSha256(String domain, String value) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
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
