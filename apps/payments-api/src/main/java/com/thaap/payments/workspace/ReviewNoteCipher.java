package com.thaap.payments.workspace;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
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
public class ReviewNoteCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AAD_VERSION = 1;

    private final byte[] encryptionKey;
    private final String currentKeyVersion;
    private final Map<String, byte[]> decryptionKeys;
    private final SecureRandom secureRandom;

    @Autowired
    public ReviewNoteCipher(
            @Value("${thaap.workspace.notes.encryption-key:}") String encodedEncryptionKey,
            @Value("${thaap.workspace.notes.key-version:v1}") String currentKeyVersion,
            @Value("${thaap.workspace.notes.decryption-keys:}") String encodedDecryptionKeys) {
        this(encodedEncryptionKey, currentKeyVersion, encodedDecryptionKeys, new SecureRandom());
    }

    ReviewNoteCipher(String encodedEncryptionKey, String currentKeyVersion,
            String encodedDecryptionKeys, SecureRandom secureRandom) {
        this.encryptionKey = decodeKey(encodedEncryptionKey,
                "thaap.workspace.notes.encryption-key", true);
        if (encryptionKey.length == KEY_BYTES && !validVersion(currentKeyVersion)) {
            throw new IllegalArgumentException("Review note key version is invalid");
        }
        this.currentKeyVersion = currentKeyVersion;
        this.decryptionKeys = buildKeyring(encodedDecryptionKeys);
        this.secureRandom = secureRandom;
    }

    public EncryptedNote encrypt(String plaintext, UUID merchantId, UUID reviewCaseId,
            UUID noteId, UUID authorOperatorId, Instant createdAt) {
        requireConfigured();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, reviewCaseId, noteId, authorOperatorId, createdAt));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedNote(Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce), currentKeyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Review note could not be encrypted", exception);
        }
    }

    public String decrypt(EncryptedNote encrypted, UUID merchantId, UUID reviewCaseId,
            UUID noteId, UUID authorOperatorId, Instant createdAt) {
        byte[] key = decryptionKeys.get(encrypted.keyVersion());
        if (key == null) {
            throw new IllegalStateException("Review note key version is unavailable");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(encrypted.nonce());
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("Review note nonce is invalid");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(merchantId, reviewCaseId, noteId, authorOperatorId, createdAt));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertext()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new IllegalStateException("Review note authentication failed", exception);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalStateException("Review note could not be decrypted", exception);
        }
    }

    public boolean isConfigured() {
        return encryptionKey.length == KEY_BYTES && validVersion(currentKeyVersion);
    }

    public boolean canDecrypt(String version) {
        return version != null && decryptionKeys.containsKey(version);
    }

    public String currentKeyVersion() {
        return currentKeyVersion;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Review note encryption is not configured");
        }
    }

    private byte[] aad(UUID merchantId, UUID reviewCaseId, UUID noteId,
            UUID authorOperatorId, Instant createdAt) {
        Instant canonical = createdAt.truncatedTo(ChronoUnit.MICROS);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(AAD_VERSION);
            writeUuid(output, merchantId);
            writeUuid(output, reviewCaseId);
            writeUuid(output, noteId);
            writeUuid(output, authorOperatorId);
            output.writeLong(canonical.getEpochSecond());
            output.writeInt(canonical.getNano() / 1_000);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Review note associated data could not be encoded", exception);
        }
    }

    private void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
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
                            "thaap.workspace.notes.decryption-keys must use version=base64 entries");
                }
                String version = trimmed.substring(0, separator).trim();
                if (!validVersion(version) || keys.containsKey(version)) {
                    throw new IllegalArgumentException("Review note key versions must be valid and unique");
                }
                keys.put(version, decodeKey(trimmed.substring(separator + 1).trim(),
                        "thaap.workspace.notes.decryption-keys[" + version + "]", false));
            }
        }
        if (encryptionKey.length == KEY_BYTES) {
            byte[] existing = keys.get(currentKeyVersion);
            if (existing != null && !Arrays.equals(existing, encryptionKey)) {
                throw new IllegalArgumentException("Current review note key conflicts with keyring");
            }
            keys.put(currentKeyVersion, encryptionKey);
        }
        return immutableKeyring(keys);
    }

    private Map<String, byte[]> immutableKeyring(Map<String, byte[]> keys) {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        keys.forEach((version, key) -> copy.put(version, key.clone()));
        return Collections.unmodifiableMap(copy);
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

    public record EncryptedNote(String ciphertext, String nonce, String keyVersion) {
    }
}
