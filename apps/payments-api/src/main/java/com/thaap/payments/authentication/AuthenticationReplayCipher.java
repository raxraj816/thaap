package com.thaap.payments.authentication;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationReplayCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String PURPOSE = "thaap:authentication-replay:v1:";

    private final byte[] encryptionKey;
    private final String currentKeyVersion;
    private final Map<String, byte[]> decryptionKeys;
    private final SecureRandom secureRandom;

    @Autowired
    public AuthenticationReplayCipher(
            @Value("${thaap.authentication.replay-encryption-key:}") String encodedEncryptionKey,
            @Value("${thaap.authentication.replay-key-version:v1}") String currentKeyVersion,
            @Value("${thaap.authentication.replay-decryption-keys:}") String encodedDecryptionKeys) {
        this(encodedEncryptionKey, currentKeyVersion, encodedDecryptionKeys, new SecureRandom());
    }

    AuthenticationReplayCipher(String encodedEncryptionKey, String currentKeyVersion,
            String encodedDecryptionKeys, SecureRandom secureRandom) {
        this.encryptionKey = decodeKey(encodedEncryptionKey, "thaap.authentication.replay-encryption-key", true);
        if (encryptionKey.length == KEY_BYTES && !validVersion(currentKeyVersion)) {
            throw new IllegalArgumentException("Authentication replay key version is invalid");
        }
        this.currentKeyVersion = currentKeyVersion;
        this.decryptionKeys = buildKeyring(encodedDecryptionKeys);
        this.secureRandom = secureRandom;
    }

    public EncryptedReplay encrypt(String plaintext, UUID merchantId, UUID recordId) {
        requireConfigured();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, recordId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedReplay(Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce), currentKeyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Authentication replay could not be encrypted", exception);
        }
    }

    public String decrypt(EncryptedReplay replay, UUID merchantId, UUID recordId) {
        byte[] key = decryptionKeys.get(replay.keyVersion());
        if (key == null) {
            throw new IllegalStateException("Authentication replay key version is unavailable");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(replay.nonce());
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("Invalid authentication replay nonce");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, recordId));
            return new String(cipher.doFinal(Base64.getDecoder().decode(replay.ciphertext())),
                    StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new IllegalStateException("Authentication replay authentication failed", exception);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalStateException("Authentication replay could not be decrypted", exception);
        }
    }

    public boolean isConfigured() {
        return encryptionKey.length == KEY_BYTES && validVersion(currentKeyVersion);
    }

    public boolean canDecrypt(String keyVersion) {
        return keyVersion != null && decryptionKeys.containsKey(keyVersion);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Authentication replay encryption is not configured");
        }
    }

    private Map<String, byte[]> buildKeyring(String encodedKeys) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (encodedKeys != null && !encodedKeys.isBlank()) {
            for (String entry : encodedKeys.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 1 || separator == trimmed.length() - 1) {
                    throw new IllegalArgumentException(
                            "thaap.authentication.replay-decryption-keys must use version=base64 entries");
                }
                String version = trimmed.substring(0, separator).trim();
                if (!validVersion(version) || keys.containsKey(version)) {
                    throw new IllegalArgumentException("Authentication replay key versions must be valid and unique");
                }
                keys.put(version, decodeKey(trimmed.substring(separator + 1).trim(),
                        "thaap.authentication.replay-decryption-keys[" + version + "]", false));
            }
        }
        if (encryptionKey.length == KEY_BYTES) {
            byte[] existing = keys.get(currentKeyVersion);
            if (existing != null && !Arrays.equals(existing, encryptionKey)) {
                throw new IllegalArgumentException("Current authentication replay key conflicts with keyring");
            }
            keys.put(currentKeyVersion, encryptionKey);
        }
        return Map.copyOf(keys);
    }

    private byte[] decodeKey(String encoded, String property, boolean optional) {
        if (encoded == null || encoded.isBlank()) {
            if (optional) {
                return new byte[0];
            }
            throw new IllegalArgumentException(property + " is required");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != KEY_BYTES || allZero(decoded)) {
                throw new IllegalArgumentException("key must be a non-zero 256-bit value");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(property + " must be a Base64-encoded 256-bit key", exception);
        }
    }

    private boolean allZero(byte[] key) {
        for (byte value : key) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean validVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9._-]{1,32}");
    }

    private byte[] aad(UUID merchantId, UUID recordId) {
        return (PURPOSE + merchantId + ":" + recordId).getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedReplay(String ciphertext, String nonce, String keyVersion) {
    }
}
