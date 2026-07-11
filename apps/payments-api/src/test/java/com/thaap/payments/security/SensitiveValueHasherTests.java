package com.thaap.payments.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SensitiveValueHasherTests {

    @Test
    void keyringPreservesCurrentThenConfiguredPreviousVersionOrder() {
        SensitiveValueHasher hasher = new SensitiveValueHasher(
                "pepper-v3",
                "v3",
                encoded("v2", "pepper-v2") + "," + encoded("v1", "pepper-v1"));

        assertThat(hasher.hmacSha256Keyring("test.domain", "external-key"))
                .extracting(SensitiveValueHasher.VersionedDigest::keyVersion)
                .containsExactly("v3", "v2", "v1");
    }

    @Test
    void duplicateKeyMaterialAcrossVersionLabelsFailsClosed() {
        String duplicateMaterial = encoded("v1", "same-pepper");

        assertThatThrownBy(() ->
                new SensitiveValueHasher("same-pepper", "v2", duplicateMaterial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct key material");
    }

    private String encoded(String version, String pepper) {
        return version + "=" + Base64.getEncoder().encodeToString(
                pepper.getBytes(StandardCharsets.UTF_8));
    }
}
