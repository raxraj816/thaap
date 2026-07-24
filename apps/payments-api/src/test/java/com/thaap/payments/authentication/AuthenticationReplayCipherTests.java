package com.thaap.payments.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationReplayCipherTests {

    private static final String V1_KEY = key('1');
    private static final String V2_KEY = key('2');

    @Test
    void replayIsAuthenticatedToPurposeMerchantAndRecord() {
        AuthenticationReplayCipher cipher = new AuthenticationReplayCipher(
                V1_KEY, "v1", "", new SecureRandom());
        UUID merchantId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        AuthenticationReplayCipher.EncryptedReplay replay = cipher.encrypt(
                "{\"clientSecret\":\"authc_secret\"}", merchantId, recordId);

        assertThat(replay.ciphertext()).doesNotContain("authc_secret");
        assertThat(cipher.decrypt(replay, merchantId, recordId))
                .isEqualTo("{\"clientSecret\":\"authc_secret\"}");
        assertThatThrownBy(() -> cipher.decrypt(replay, UUID.randomUUID(), recordId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed");
        assertThatThrownBy(() -> cipher.decrypt(replay, merchantId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void rotationReadsOldReplayOnlyWhileOldVersionRemainsConfigured() {
        AuthenticationReplayCipher oldCipher = new AuthenticationReplayCipher(
                V1_KEY, "v1", "", new SecureRandom());
        UUID merchantId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        AuthenticationReplayCipher.EncryptedReplay replay = oldCipher.encrypt(
                "protected-response", merchantId, recordId);

        AuthenticationReplayCipher rotated = new AuthenticationReplayCipher(
                V2_KEY, "v2", "v1=" + V1_KEY, new SecureRandom());
        assertThat(rotated.canDecrypt("v1")).isTrue();
        assertThat(rotated.decrypt(replay, merchantId, recordId)).isEqualTo("protected-response");

        AuthenticationReplayCipher retired = new AuthenticationReplayCipher(
                V2_KEY, "v2", "", new SecureRandom());
        assertThat(retired.canDecrypt("v1")).isFalse();
        assertThatThrownBy(() -> retired.decrypt(replay, merchantId, recordId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key version is unavailable");
    }

    @Test
    void malformedDuplicateAndConflictingKeyConfigurationFailsClosed() {
        assertThatThrownBy(() -> new AuthenticationReplayCipher("bad", "v1", "", new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticationReplayCipher(
                V2_KEY, "v2", "v1=" + V1_KEY + ",v1=" + V2_KEY, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticationReplayCipher(
                V2_KEY, "v2", "v2=" + V1_KEY, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String key(char value) {
        return Base64.getEncoder().encodeToString(String.valueOf(value).repeat(32).getBytes());
    }
}
