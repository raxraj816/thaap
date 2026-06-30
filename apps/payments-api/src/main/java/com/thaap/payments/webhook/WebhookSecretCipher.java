package com.thaap.payments.webhook;

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
public class WebhookSecretCipher {

    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final byte[] encryptionKey;
    private final String keyVersion;
    private final Map<String, byte[]> decryptionKeys;
    private final SecureRandom secureRandom;

    @Autowired
    public WebhookSecretCipher(
            @Value("${thaap.webhook.secret-encryption-key:}") String encodedEncryptionKey,
            @Value("${thaap.webhook.secret-key-version:v1}") String keyVersion,
            @Value("${thaap.webhook.secret-decryption-keys:}") String encodedDecryptionKeys) {
        this(encodedEncryptionKey, keyVersion, encodedDecryptionKeys, new SecureRandom());
    }

    WebhookSecretCipher(String encodedEncryptionKey, String keyVersion, SecureRandom secureRandom) {
        this(encodedEncryptionKey, keyVersion, "", secureRandom);
    }

    WebhookSecretCipher(String encodedEncryptionKey, String keyVersion,
            String encodedDecryptionKeys, SecureRandom secureRandom) {
        this.encryptionKey = decodeCurrentKey(encodedEncryptionKey);
        if (encryptionKey.length == AES_256_KEY_BYTES && (keyVersion == null || keyVersion.isBlank())) {
            throw new IllegalArgumentException("Webhook encryption key version must not be blank");
        }
        this.keyVersion = keyVersion;
        this.decryptionKeys = buildDecryptionKeyring(encodedDecryptionKeys);
        this.secureRandom = secureRandom;
    }

    public WebhookEncryptedSecret encrypt(String rawSecret, UUID merchantId, UUID endpointId) {
        requireConfigured();
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, endpointId));
            byte[] ciphertext = cipher.doFinal(rawSecret.getBytes(StandardCharsets.UTF_8));
            return new WebhookEncryptedSecret(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new WebhookSecretException("Webhook signing secret could not be encrypted",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE, exception);
        }
    }

    public String decrypt(WebhookEndpoint endpoint) {
        requireConfigured();
        if (endpoint.getSigningSecretCiphertext() == null
                || endpoint.getSigningSecretNonce() == null
                || endpoint.getSigningSecretKeyVersion() == null) {
            throw new WebhookSecretException("Webhook endpoint has no encrypted signing secret",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
        byte[] decryptionKey = decryptionKeys.get(endpoint.getSigningSecretKeyVersion());
        if (decryptionKey == null) {
            throw new WebhookSecretException("Webhook endpoint secret key version is unavailable",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(endpoint.getSigningSecretNonce());
            byte[] ciphertext = Base64.getDecoder().decode(endpoint.getSigningSecretCiphertext());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(endpoint.getMerchantId(), endpoint.getId()));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new WebhookSecretException("Webhook signing secret authentication failed",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_TAMPERED, exception);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new WebhookSecretException("Webhook signing secret could not be decrypted",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE, exception);
        }
    }

    public boolean isConfigured() {
        return encryptionKey.length == AES_256_KEY_BYTES
                && keyVersion != null
                && !keyVersion.isBlank();
    }

    public boolean canDecryptKeyVersion(String candidateKeyVersion) {
        return candidateKeyVersion != null && decryptionKeys.containsKey(candidateKeyVersion);
    }

    public boolean isCurrentKeyVersion(String candidateKeyVersion) {
        return keyVersion != null && keyVersion.equals(candidateKeyVersion);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new WebhookSecretException("Webhook signing-secret encryption key is not configured",
                    WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        }
    }

    private byte[] decodeCurrentKey(String encodedEncryptionKey) {
        if (encodedEncryptionKey == null || encodedEncryptionKey.isBlank()) {
            return new byte[0];
        }
        return decodeRequiredKey(encodedEncryptionKey, "thaap.webhook.secret-encryption-key");
    }

    private byte[] decodeRequiredKey(String encodedKey, String propertyName) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != AES_256_KEY_BYTES) {
                throw new IllegalArgumentException("Webhook encryption key must decode to 32 bytes");
            }
            if (isAllZero(decoded)) {
                throw new IllegalArgumentException("Webhook encryption key must not be all zeros");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    propertyName + " must contain a Base64-encoded 256-bit key", exception);
        }
    }

    private Map<String, byte[]> buildDecryptionKeyring(String encodedDecryptionKeys) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (encodedDecryptionKeys != null && !encodedDecryptionKeys.isBlank()) {
            for (String entry : encodedDecryptionKeys.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 1 || separator == trimmed.length() - 1) {
                    throw new IllegalArgumentException(
                            "thaap.webhook.secret-decryption-keys must use version=base64 entries");
                }
                String version = trimmed.substring(0, separator).trim();
                String encodedKey = trimmed.substring(separator + 1).trim();
                if (version.isEmpty() || keys.containsKey(version)) {
                    throw new IllegalArgumentException(
                            "Webhook decryption key versions must be non-blank and unique");
                }
                keys.put(version, decodeRequiredKey(encodedKey,
                        "thaap.webhook.secret-decryption-keys[" + version + "]"));
            }
        }
        if (encryptionKey.length == AES_256_KEY_BYTES) {
            byte[] existing = keys.get(keyVersion);
            if (existing != null && !Arrays.equals(existing, encryptionKey)) {
                throw new IllegalArgumentException(
                        "Current webhook key version conflicts with the decryption keyring");
            }
            keys.put(keyVersion, encryptionKey);
        }
        return Map.copyOf(keys);
    }

    private boolean isAllZero(byte[] key) {
        for (byte value : key) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private byte[] aad(UUID merchantId, UUID endpointId) {
        return (merchantId + ":" + endpointId).getBytes(StandardCharsets.UTF_8);
    }
}
