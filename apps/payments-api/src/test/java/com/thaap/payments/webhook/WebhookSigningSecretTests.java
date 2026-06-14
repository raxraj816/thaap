package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WebhookSigningSecretTests {

    @Test
    void generatedSecretsUseUrlSafeSecureRandomMaterial() {
        var secrets = IntStream.range(0, 100)
                .mapToObj(ignored -> WebhookSigningSecret.generate())
                .toList();

        assertThat(secrets)
                .allSatisfy(secret -> {
                    assertThat(secret).startsWith("whsec_");
                    assertThat(secret.substring("whsec_".length())).hasSize(43);
                    assertThat(secret.substring("whsec_".length())).matches("[A-Za-z0-9_-]+");
                });
        assertThat(secrets).doesNotHaveDuplicates();
    }
}
