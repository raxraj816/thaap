package com.thaap.payments.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SecretHasher {

    private SecretHasher() {
    }

    public static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String prefix(String value) {
        var prefixLength = value.length() <= 12 ? Math.max(1, value.length() / 2) : 12;
        return value.substring(0, prefixLength);
    }
}
