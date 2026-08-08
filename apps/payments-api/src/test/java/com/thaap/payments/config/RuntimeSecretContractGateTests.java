package com.thaap.payments.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the runtime secret contract one variable at a time.
 *
 * <p>The plan's obligation is specific about this: the refusal is proven <em>per variable</em>
 * rather than for one representative case. That is not pedantry. A validator written against a
 * list is only as good as the list, and the way this class of check fails in practice is that
 * seven of eight secrets are enforced and the eighth — added later, by someone else, in a hurry —
 * is enforced by nothing. Driving every case from {@link RequiredRuntimeSecret#values()} means a
 * secret cannot be added to the contract without arriving with its own proof.
 */
class RuntimeSecretContractGateTests {

    @ParameterizedTest
    @EnumSource(RequiredRuntimeSecret.class)
    void refusesAnAbsentSecret(RequiredRuntimeSecret secret) {
        Map<String, String> configuration = deployableConfiguration();
        configuration.remove(secret.property());

        List<String> violations = violations(configuration);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst())
                .contains(secret.environmentVariable())
                .contains(secret.property())
                .contains("is not set");
    }

    @ParameterizedTest
    @EnumSource(RequiredRuntimeSecret.class)
    void refusesAnEmptySecret(RequiredRuntimeSecret secret) {
        // The state every one of these is in today: application.yml defaults them to "", which is
        // correct on a laptop and is the exact input that makes a deployed instance encrypt
        // nothing while reporting healthy.
        Map<String, String> configuration = deployableConfiguration();
        configuration.put(secret.property(), "");

        List<String> violations = violations(configuration);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst()).contains(secret.environmentVariable());
    }

    @ParameterizedTest
    @EnumSource(RequiredRuntimeSecret.class)
    void refusesABlankSecret(RequiredRuntimeSecret secret) {
        Map<String, String> configuration = deployableConfiguration();
        configuration.put(secret.property(), "   ");

        assertThat(violations(configuration)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(RequiredRuntimeSecret.class)
    void refusesASecretOneUnitShortOfItsRequiredShape(RequiredRuntimeSecret secret) {
        // The boundary itself, not a gesture at it. A thirty-one-byte key and a thirty-one
        // character token are what a truncated copy-paste actually produces.
        Map<String, String> configuration = deployableConfiguration();
        configuration.put(secret.property(), switch (secret.shape()) {
            case OPAQUE_TOKEN -> "a".repeat(RequiredRuntimeSecret.MINIMUM_OPAQUE_TOKEN_LENGTH - 1);
            case AES_256_KEY -> Base64.getEncoder().encodeToString(
                    new byte[RequiredRuntimeSecret.AES_256_KEY_BYTES - 1]);
        });

        List<String> violations = violations(configuration);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst()).contains(secret.environmentVariable());
    }

    @ParameterizedTest
    @EnumSource(RequiredRuntimeSecret.class)
    void acceptsASecretExactlyAtItsRequiredShape(RequiredRuntimeSecret secret) {
        // The other side of the boundary. A rule that also rejected the minimum would pass every
        // refusal test above and block every deployment.
        Map<String, String> configuration = deployableConfiguration();
        configuration.put(secret.property(), switch (secret.shape()) {
            case OPAQUE_TOKEN -> "a".repeat(RequiredRuntimeSecret.MINIMUM_OPAQUE_TOKEN_LENGTH - 1)
                    + secret.ordinal();
            case AES_256_KEY -> distinctKey(secret);
        });

        assertThat(violations(configuration)).isEmpty();
    }

    @Test
    void refusesAPlaceholderNobodyRememberedToList() {
        // Deliberately scoped to opaque tokens, because the marker rule cannot reach a Base64 key
        // and is not pretending to: every marker contains a hyphen or a letter outside the
        // hexadecimal alphabet, which is what keeps a generated secret from tripping it by
        // accident. AES keys are covered by the shape rule and by the published-value list, and
        // `refusesEveryValueThisRepositoryHasEverPublished` proves that half.
        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            if (secret.shape() != RequiredRuntimeSecret.Shape.OPAQUE_TOKEN) {
                continue;
            }
            Map<String, String> configuration = deployableConfiguration();
            configuration.put(secret.property(),
                    "change-me-before-anyone-notices-this-is-still-here");

            List<String> violations = violations(configuration);

            assertThat(violations).as("%s", secret.environmentVariable()).hasSize(1);
            assertThat(violations.getFirst()).contains("placeholder");
        }
    }

    @Test
    void acceptsAFullyDeployableConfiguration() {
        assertThat(violations(deployableConfiguration())).isEmpty();
    }

    @Test
    void reportsEveryViolationAtOnceRatherThanTheFirst() {
        // Eight failed deployments to learn about eight missing secrets is how an operator ends up
        // looking for the flag that turns the check off.
        assertThat(violations(Map.of())).hasSize(RequiredRuntimeSecret.values().length);
    }

    @Test
    void refusesTheSameValueUsedForTwoSecrets() {
        Map<String, String> configuration = deployableConfiguration();
        String shared = configuration.get(RequiredRuntimeSecret.HASH_PEPPER.property());
        configuration.put(RequiredRuntimeSecret.CORRELATION_PEPPER.property(), shared);

        List<String> violations = violations(configuration);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst())
                .contains(RequiredRuntimeSecret.CORRELATION_PEPPER.environmentVariable())
                .contains(RequiredRuntimeSecret.HASH_PEPPER.environmentVariable())
                .contains("reuses the value");
    }

    @Test
    void refusesEveryValueThisRepositoryHasEverPublished() {
        for (String published : RequiredRuntimeSecret.PUBLISHED_REPOSITORY_VALUES) {
            for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
                Map<String, String> configuration = deployableConfiguration();
                configuration.put(secret.property(), published);

                assertThat(violations(configuration))
                        .as("a published value must be refused for %s",
                                secret.environmentVariable())
                        .isNotEmpty();
            }
        }
    }

    @Test
    void namesTheVariableAndTheFixWithoutEverPrintingTheValue() {
        // A validator that quotes the secret it rejected writes that secret into every log
        // aggregator the startup output reaches, turning a configuration mistake into a
        // disclosure. The message has to be actionable and still say nothing.
        String publishedKey = "AXqcQyEyp+PaWc4kNkglchMezuXhDCBnqoQT/BRKEo0=";
        Map<String, String> configuration = deployableConfiguration();
        configuration.put(
                RequiredRuntimeSecret.WEBHOOK_SECRET_ENCRYPTION_KEY.property(), publishedKey);

        List<String> violations = violations(configuration);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst())
                .contains("THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY")
                .contains("openssl rand -base64 32")
                .doesNotContain(publishedKey);
    }

    @Test
    void staysOutOfTheWayWhenEnforcementIsOff() {
        // The local profile has none of these values and must keep starting. A contract that made
        // development require a secrets ceremony would be routed around within a week.
        new RuntimeSecretContractGate().postProcessEnvironment(new MockEnvironment(), null);
    }

    @Test
    void refusesStartupWhenEnforcementIsOnAndTheContractIsUnmet() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(RuntimeSecretContractGate.ENFORCEMENT_PROPERTY, "true");

        Throwable refusal = catchThrowable(
                () -> new RuntimeSecretContractGate().postProcessEnvironment(environment, null));

        assertThat(refusal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("THAAP_ADMIN_BOOTSTRAP_TOKEN")
                .hasMessageContaining("RUNTIME_CONFIGURATION_REFERENCE.md");
    }

    @Test
    void everySecretDocumentsWhatItProtectsAndHowToGenerateOne() {
        // A refusal an operator cannot act on is an outage rather than a control.
        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            assertThat(secret.purpose()).isNotBlank();
            assertThat(secret.shape().generatorHint()).startsWith("openssl rand");
        }
    }

    private List<String> violations(Map<String, String> configuration) {
        return RuntimeSecretContractGate.violations(configuration::get);
    }

    /** A configuration that satisfies every rule, so each test can break exactly one thing. */
    private Map<String, String> deployableConfiguration() {
        Map<String, String> configuration = new HashMap<>();
        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            configuration.put(secret.property(), switch (secret.shape()) {
                case OPAQUE_TOKEN -> "9f3c" + "0123456789abcdef".repeat(2) + secret.ordinal();
                case AES_256_KEY -> distinctKey(secret);
            });
        }
        return configuration;
    }

    /** Thirty-two bytes, distinct per secret, so distinctness never fails by accident. */
    private String distinctKey(RequiredRuntimeSecret secret) {
        byte[] filler = "0123456789abcdef0123456789abcde".getBytes(StandardCharsets.UTF_8);
        byte[] distinct = new byte[RequiredRuntimeSecret.AES_256_KEY_BYTES];
        System.arraycopy(filler, 0, distinct, 0, filler.length);
        distinct[distinct.length - 1] = (byte) ('A' + secret.ordinal());
        return Base64.getEncoder().encodeToString(distinct);
    }
}
