package com.thaap.payments.authentication;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.idempotency.IdempotencyRepository;
import com.thaap.payments.security.SensitiveValueHasher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticationKeyReadinessTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void activeChallengeFailsStartupWhenItsHmacVersionIsMissing() {
        AuthenticationChallengeRepository repository = mock(AuthenticationChallengeRepository.class);
        when(repository.findRequiredSecretKeyVersions(CLOCK.instant())).thenReturn(List.of("retired-v1"));
        SensitiveValueHasher hasher = new SensitiveValueHasher("current-pepper", "v2", "");

        AuthenticationChallengeKeyConfiguration readiness =
                new AuthenticationChallengeKeyConfiguration(repository, hasher, CLOCK);

        assertThatThrownBy(readiness::validateRequiredKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired-v1");
    }

    @Test
    void configuredPreviousHmacVersionKeepsAnActiveChallengeReadable() {
        AuthenticationChallengeRepository repository = mock(AuthenticationChallengeRepository.class);
        when(repository.findRequiredSecretKeyVersions(CLOCK.instant())).thenReturn(List.of("v1"));
        String previous = Base64.getEncoder().encodeToString("previous-pepper".getBytes());
        SensitiveValueHasher hasher = new SensitiveValueHasher(
                "current-pepper", "v2", "v1=" + previous);

        AuthenticationChallengeKeyConfiguration readiness =
                new AuthenticationChallengeKeyConfiguration(repository, hasher, CLOCK);

        assertThatCode(readiness::validateRequiredKeys).doesNotThrowAnyException();
    }

    @Test
    void protectedCreationReplayFailsStartupWhenItsEncryptionVersionIsMissing() {
        IdempotencyRepository repository = mock(IdempotencyRepository.class);
        when(repository.findActiveProtectedSensitiveReplays(CLOCK.instant())).thenReturn(List.of(
                "{\"format\":\"authentication-replay-v1\",\"ciphertext\":\"AA==\","
                        + "\"nonce\":\"AAAAAAAAAAAAAAAA\",\"keyVersion\":\"retired-v1\"}"));
        AuthenticationReplayCipher cipher = cipher("v2", key(2), "");

        AuthenticationReplayKeyConfiguration readiness = new AuthenticationReplayKeyConfiguration(
                repository, cipher, new ObjectMapper(), CLOCK);

        assertThatThrownBy(readiness::validateRequiredKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key version is unavailable");
    }

    @Test
    void configuredPreviousReplayVersionPassesReadinessAndMalformedEnvelopeFailsClosed() {
        IdempotencyRepository repository = mock(IdempotencyRepository.class);
        AuthenticationReplayCipher cipher = cipher("v2", key(2), "v1=" + key(1));
        AuthenticationReplayKeyConfiguration readiness = new AuthenticationReplayKeyConfiguration(
                repository, cipher, new ObjectMapper(), CLOCK);

        when(repository.findActiveProtectedSensitiveReplays(CLOCK.instant())).thenReturn(List.of(
                "{\"format\":\"authentication-replay-v1\",\"ciphertext\":\"AA==\","
                        + "\"nonce\":\"AAAAAAAAAAAAAAAA\",\"keyVersion\":\"v1\"}",
                "{\"format\":\"sensitive-replay-v1\",\"ciphertext\":\"AA==\","
                        + "\"nonce\":\"AAAAAAAAAAAAAAAA\",\"keyVersion\":\"v1\"}"));
        assertThatCode(readiness::validateRequiredKeys).doesNotThrowAnyException();

        when(repository.findActiveProtectedSensitiveReplays(CLOCK.instant()))
                .thenReturn(List.of("{\"format\":\"wrong\",\"keyVersion\":\"v1\"}"));
        assertThatThrownBy(readiness::validateRequiredKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key version is unavailable");
    }

    private AuthenticationReplayCipher cipher(String version, String currentKey, String previousKeys) {
        return new AuthenticationReplayCipher(currentKey, version, previousKeys, new SecureRandom());
    }

    private String key(int seed) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (seed + index);
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
