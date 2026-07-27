package com.thaap.payments.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewNoteCipherTests {

    private static final String KEY_V1 = Base64.getEncoder().encodeToString(
            "review-note-test-key-material-v1".getBytes());
    private static final String KEY_V2 = Base64.getEncoder().encodeToString(
            "review-note-test-key-material-v2".getBytes());

    @Test
    void exactAssociatedDataRoundTripsAndEveryIdentityBoundaryAuthenticates() {
        ReviewNoteCipher cipher = new ReviewNoteCipher(KEY_V2, "v2", "v1=" + KEY_V1,
                new SecureRandom());
        UUID merchantId = UUID.randomUUID();
        UUID reviewCaseId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-25T01:02:03.123456Z");
        ReviewNoteCipher.EncryptedNote encrypted = cipher.encrypt("bounded analyst context",
                merchantId, reviewCaseId, noteId, authorId, createdAt);

        assertThat(encrypted.keyVersion()).isEqualTo("v2");
        assertThat(Base64.getDecoder().decode(encrypted.nonce())).hasSize(12);
        assertThat(cipher.decrypt(encrypted, merchantId, reviewCaseId, noteId, authorId, createdAt))
                .isEqualTo("bounded analyst context");

        assertAuthenticationFailure(cipher, encrypted, UUID.randomUUID(), reviewCaseId,
                noteId, authorId, createdAt);
        assertAuthenticationFailure(cipher, encrypted, merchantId, UUID.randomUUID(),
                noteId, authorId, createdAt);
        assertAuthenticationFailure(cipher, encrypted, merchantId, reviewCaseId,
                UUID.randomUUID(), authorId, createdAt);
        assertAuthenticationFailure(cipher, encrypted, merchantId, reviewCaseId,
                noteId, UUID.randomUUID(), createdAt);
        assertAuthenticationFailure(cipher, encrypted, merchantId, reviewCaseId,
                noteId, authorId, createdAt.plusNanos(1_000));
    }

    @Test
    void rotationReadsRetainedVersionWhileUnknownVersionAndTamperFailClosed() {
        UUID merchantId = UUID.randomUUID();
        UUID reviewCaseId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-25T01:02:03.123456Z");
        ReviewNoteCipher oldCipher = new ReviewNoteCipher(KEY_V1, "v1", "", new SecureRandom());
        ReviewNoteCipher.EncryptedNote old = oldCipher.encrypt("old key note", merchantId,
                reviewCaseId, noteId, authorId, createdAt);
        ReviewNoteCipher rotated = new ReviewNoteCipher(KEY_V2, "v2", "v1=" + KEY_V1,
                new SecureRandom());

        assertThat(rotated.decrypt(old, merchantId, reviewCaseId, noteId, authorId, createdAt))
                .isEqualTo("old key note");
        assertThatThrownBy(() -> rotated.decrypt(new ReviewNoteCipher.EncryptedNote(
                old.ciphertext(), old.nonce(), "missing"), merchantId, reviewCaseId,
                noteId, authorId, createdAt)).hasMessageContaining("unavailable");
        String tampered = old.ciphertext().substring(0, old.ciphertext().length() - 2) + "AA";
        assertThatThrownBy(() -> rotated.decrypt(new ReviewNoteCipher.EncryptedNote(
                tampered, old.nonce(), old.keyVersion()), merchantId, reviewCaseId,
                noteId, authorId, createdAt)).hasMessageContaining("Review note");
    }

    @Test
    void configurationRejectsMalformedShortZeroDuplicateAndConflictingKeys() {
        String zero = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatThrownBy(() -> new ReviewNoteCipher("not-base64", "v1", "", new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewNoteCipher(zero, "v1", "", new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewNoteCipher(KEY_V1, "bad version", "", new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewNoteCipher(KEY_V1, "v1",
                "v2=" + KEY_V2 + ",v2=" + KEY_V1, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewNoteCipher(KEY_V1, "v1",
                "v1=" + KEY_V2, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertAuthenticationFailure(ReviewNoteCipher cipher,
            ReviewNoteCipher.EncryptedNote encrypted, UUID merchantId, UUID reviewCaseId,
            UUID noteId, UUID authorId, Instant createdAt) {
        assertThatThrownBy(() -> cipher.decrypt(encrypted, merchantId, reviewCaseId,
                noteId, authorId, createdAt)).hasMessageContaining("authentication failed");
    }
}
