package com.thaap.payments.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class WebhookSigner {

    public WebhookSignature sign(String rawSecret, String payload, Instant attemptTime) {
        long timestampSeconds = attemptTime.getEpochSecond();
        String value = hmac(rawSecret, timestampSeconds + "." + payload);
        return new WebhookSignature(timestampSeconds, value,
                "t=" + timestampSeconds + ",v1=" + value);
    }

    public boolean verify(String rawSecret, String payload, long timestampSeconds, String expectedHex) {
        byte[] actual = HexFormat.of().parseHex(hmac(rawSecret, timestampSeconds + "." + payload));
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(expectedHex);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(actual, expected);
    }

    private String hmac(String rawSecret, String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rawSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
