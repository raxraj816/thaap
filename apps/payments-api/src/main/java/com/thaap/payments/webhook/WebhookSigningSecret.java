package com.thaap.payments.webhook;

import java.security.SecureRandom;
import java.util.Base64;

final class WebhookSigningSecret {

    private static final int SECRET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE_BASE64 = Base64.getUrlEncoder().withoutPadding();

    private WebhookSigningSecret() {
    }

    static String generate() {
        var bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return "whsec_" + URL_SAFE_BASE64.encodeToString(bytes);
    }
}
