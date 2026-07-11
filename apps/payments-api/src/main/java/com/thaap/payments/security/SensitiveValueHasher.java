package com.thaap.payments.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SensitiveValueHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String currentKeyVersion;
    private final Map<String, SecretKeySpec> keyring;

    public SensitiveValueHasher(
            @Value("${thaap.security.hash-pepper}") String pepper,
            @Value("${thaap.security.hash-pepper-version:v1}") String currentKeyVersion,
            @Value("${thaap.security.previous-hash-peppers:}") String previousHashPeppers) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("thaap.security.hash-pepper must be configured");
        }
        if (!validKeyVersion(currentKeyVersion)) {
            throw new IllegalStateException(
                    "thaap.security.hash-pepper-version must match "
                            + "[A-Za-z0-9._-]{1,32}");
        }
        this.currentKeyVersion = currentKeyVersion;
        this.keyring = buildKeyring(pepper, currentKeyVersion, previousHashPeppers);
    }

    public String hmacSha256(String domain, String value) {
        return hmacSha256(domain, value, currentKeyVersion);
    }

    public String hmacSha256(String domain, String value, String keyVersion) {
        SecretKeySpec keySpec = keyring.get(keyVersion);
        if (keySpec == null) {
            throw new IllegalStateException(
                    "Sensitive-value hash key version is unavailable: " + keyVersion);
        }
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

    public String currentKeyVersion() {
        return currentKeyVersion;
    }

    public boolean canHashWith(String keyVersion) {
        return keyring.containsKey(keyVersion);
    }

    public List<VersionedDigest> hmacSha256Keyring(String domain, String value) {
        List<VersionedDigest> digests = new ArrayList<>();
        Set<String> seenDigests = new HashSet<>();
        String currentDigest = hmacSha256(domain, value, currentKeyVersion);
        digests.add(new VersionedDigest(currentKeyVersion, currentDigest));
        seenDigests.add(currentDigest);
        for (String keyVersion : keyring.keySet()) {
            if (!keyVersion.equals(currentKeyVersion)) {
                String digest = hmacSha256(domain, value, keyVersion);
                if (!seenDigests.add(digest)) {
                    throw new IllegalStateException(
                            "Distinct hash key versions produced the same identity digest");
                }
                digests.add(new VersionedDigest(keyVersion, digest));
            }
        }
        return List.copyOf(digests);
    }

    private Map<String, SecretKeySpec> buildKeyring(String currentPepper,
            String currentVersion, String previousHashPeppers) {
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        List<byte[]> keyMaterials = new ArrayList<>();
        byte[] currentKeyMaterial = currentPepper.getBytes(StandardCharsets.UTF_8);
        keys.put(currentVersion, keySpec(currentKeyMaterial));
        keyMaterials.add(currentKeyMaterial);
        if (previousHashPeppers == null || previousHashPeppers.isBlank()) {
            return immutableOrderedCopy(keys);
        }
        for (String entry : previousHashPeppers.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 1 || separator == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "thaap.security.previous-hash-peppers must use "
                                + "version=base64 entries");
            }
            String version = trimmed.substring(0, separator).trim();
            String encodedPepper = trimmed.substring(separator + 1).trim();
            if (!validKeyVersion(version) || keys.containsKey(version)) {
                throw new IllegalArgumentException(
                        "Sensitive-value hash key versions must match "
                                + "[A-Za-z0-9._-]{1,32} and be unique");
            }
            byte[] decodedPepper;
            try {
                decodedPepper = Base64.getDecoder().decode(encodedPepper);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "thaap.security.previous-hash-peppers[" + version
                                + "] must be Base64 encoded",
                        exception);
            }
            if (decodedPepper.length == 0) {
                throw new IllegalArgumentException(
                        "Previous sensitive-value hash peppers must not be empty");
            }
            if (keyMaterials.stream().anyMatch(
                    existing -> MessageDigest.isEqual(existing, decodedPepper))) {
                throw new IllegalArgumentException(
                        "Sensitive-value hash key versions must use distinct key material");
            }
            keys.put(version, keySpec(decodedPepper));
            keyMaterials.add(decodedPepper);
        }
        return immutableOrderedCopy(keys);
    }

    private boolean validKeyVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9._-]{1,32}");
    }

    private Map<String, SecretKeySpec> immutableOrderedCopy(
            Map<String, SecretKeySpec> keys) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    private SecretKeySpec keySpec(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
    }

    public record VersionedDigest(String keyVersion, String digest) {
    }
}
