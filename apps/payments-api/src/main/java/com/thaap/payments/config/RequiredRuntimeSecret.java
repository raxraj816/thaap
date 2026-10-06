package com.thaap.payments.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The eight configuration values a deployed THAAP instance may not run without.
 *
 * <p>Every one of them is security-relevant, and every one of them defaults to the empty string in
 * {@code application.yml} so that a developer's laptop starts without a secrets ceremony. That
 * default is correct locally and catastrophic in a deployment: an empty KEK means analyst notes,
 * webhook signing secrets and authentication replays are written by a system that believes it is
 * encrypting them, and an empty bootstrap token means merchant-creation authority is held by
 * nobody, or by everybody, depending on which code path asks.
 *
 * <p>The enum exists so the contract is enumerable rather than remembered. A secret added to the
 * application and not added here is a secret nothing enforces; {@code
 * RuntimeSecretContractDriftTests} holds this list equal to the security-relevant placeholders in
 * {@code application.yml} so that omission fails a build rather than a deployment.
 *
 * <p>Rotation variables are deliberately absent. An empty previous-key set is a valid state — it
 * is what every deployment that has never rotated looks like — and the existing rotation suites
 * prove the overlap behaviour. Requiring them would make a normal state look like a broken one.
 */
public enum RequiredRuntimeSecret {

    ADMIN_BOOTSTRAP_TOKEN(
            "thaap.admin.bootstrap-token",
            "THAAP_ADMIN_BOOTSTRAP_TOKEN",
            Shape.OPAQUE_TOKEN,
            "Merchant creation authority. Whoever holds it can mint merchants and their keys."),

    PLATFORM_BOOTSTRAP_TOKEN(
            "thaap.platform.bootstrap-token",
            "THAAP_PLATFORM_BOOTSTRAP_TOKEN",
            Shape.OPAQUE_TOKEN,
            "Platform operator authority. Whoever holds it can create operators across tenants."),

    SCENARIO_RUNNER_TOKEN(
            "thaap.scenario-runner.token",
            "THAAP_SCENARIO_RUNNER_TOKEN",
            Shape.OPAQUE_TOKEN,
            "Proves that synthetic scenario traffic came from the trusted runner rather than "
                    + "from a caller who would like their payments treated as simulated."),

    HASH_PEPPER(
            "thaap.security.hash-pepper",
            "THAAP_HASH_PEPPER",
            Shape.OPAQUE_TOKEN,
            "Keys the HMAC that hashes merchant API keys, credentials and replay identities. "
                    + "Losing it invalidates every stored digest; leaking it makes them guessable."),

    CORRELATION_PEPPER(
            "thaap.security.correlation-pepper",
            "THAAP_CORRELATION_PEPPER",
            Shape.OPAQUE_TOKEN,
            "Keys the stable pseudonyms that correlate customers and fraud across PAN, email and "
                    + "phone. It has a different lifecycle from the hash pepper on purpose: "
                    + "rotating it re-keys fraud identity rather than just credentials."),

    AUTHENTICATION_REPLAY_ENCRYPTION_KEY(
            "thaap.authentication.replay-encryption-key",
            "THAAP_AUTHENTICATION_REPLAY_ENCRYPTION_KEY",
            Shape.AES_256_KEY,
            "Encrypts stored authentication replays at rest."),

    WEBHOOK_SECRET_ENCRYPTION_KEY(
            "thaap.webhook.secret-encryption-key",
            "THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY",
            Shape.AES_256_KEY,
            "Encrypts merchant webhook signing secrets at rest. Without it a merchant cannot "
                    + "verify that a delivery came from THAAP."),

    REVIEW_NOTE_ENCRYPTION_KEY(
            "thaap.workspace.notes.encryption-key",
            "THAAP_REVIEW_NOTE_ENCRYPTION_KEY",
            Shape.AES_256_KEY,
            "Encrypts analyst review notes at rest. They contain an analyst's own words about a "
                    + "named customer, which is the most sensitive free text in the product.");

    /**
     * The minimum length for an opaque token, in characters.
     *
     * <p>Thirty-two is not a cryptographic derivation; it is the point below which a value is
     * obviously a person typing something rather than a generator producing something. The real
     * work of rejecting weak values is done by the published-value and placeholder rules below,
     * because the realistic production accident is not a short random token — it is a strong
     * token copied out of this repository's own quickstart.
     */
    static final int MINIMUM_OPAQUE_TOKEN_LENGTH = 32;

    /** AES-256, so exactly thirty-two bytes once Base64 is decoded. */
    static final int AES_256_KEY_BYTES = 32;

    /**
     * Every secret value published in this repository.
     *
     * <p>These are already public — they sit in {@code infra/docker/compose.yml}, in the two
     * READMEs and in {@code application-test.yml}, deliberately, because a stack that cannot start
     * without a secrets ceremony is a stack nobody runs. Repeating them here adds no exposure and
     * closes the gap that publishing them opens: the most likely way a real deployment ends up
     * with a known key is somebody copying the quickstart that was written to be copied.
     *
     * <p>{@code RuntimeSecretContractDriftTests} scrapes those four files and fails if any value
     * they publish is missing from this list, so a new committed default cannot quietly become an
     * accepted production secret.
     */
    static final List<String> PUBLISHED_REPOSITORY_VALUES = List.of(
            // infra/docker/compose.yml — the local development stack
            "local-admin-bootstrap-token",
            "local-platform-bootstrap-token",
            "local-scenario-runner-token",
            "local-hash-pepper-not-for-production",
            "local-correlation-pepper-not-for-production",
            "AXqcQyEyp+PaWc4kNkglchMezuXhDCBnqoQT/BRKEo0=",
            "qLPaIJTvXW+fHN7xUJTuVzw+BQuCa3zXBsFonNM56Z0=",
            "s083f4o4N0LsdH3WLDjeJa/S4Ow+StpqsYeoMs0eCb0=",
            // README.md and apps/payments-api/README.md — the documented quickstart
            "change-me-local-admin-bootstrap-token",
            "change-me-local-sensitive-value-pepper",
            // apps/payments-api/src/test/resources/application-test.yml — the suite's fixtures
            "test-admin-bootstrap-token",
            "test-platform-bootstrap-token",
            "test-scenario-runner-token",
            "test-hash-pepper-for-sensitive-value-contracts",
            "dGVzdC1hdXRoLXJlcGxheS1rZXktMzItYnl0ZXMtMTI=",
            "dGVzdC13ZWJob29rLWtleS0zMi1ieXRlcy0xMjM0NTY=",
            "dGVzdC1yZXZpZXctbm90ZS1rZXktMzItYnl0ZXMtMTI=");

    /**
     * Substrings that mark a value as never having been meant for a deployment.
     *
     * <p>This catches the placeholder nobody remembered to add to the list above. A false positive
     * costs an operator one regenerated token and a clear error message; a false negative costs a
     * production system with a key that is written down in a wiki.
     *
     * <p>Every marker either contains a hyphen or a letter outside the hexadecimal alphabet, so
     * neither {@code openssl rand -hex 32} nor {@code openssl rand -base64 32} can produce one by
     * accident at any rate worth reasoning about. That is the property being traded for here, and
     * it is why a shorter, punchier marker such as {@code xxxx} is deliberately not in the list.
     */
    static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me", "changeme", "local-", "test-", "example", "placeholder", "dummy",
            "your-", "insert-");

    private final String property;
    private final String environmentVariable;
    private final Shape shape;
    private final String purpose;

    RequiredRuntimeSecret(String property, String environmentVariable, Shape shape,
            String purpose) {
        this.property = property;
        this.environmentVariable = environmentVariable;
        this.shape = shape;
        this.purpose = purpose;
    }

    public String property() {
        return property;
    }

    public String environmentVariable() {
        return environmentVariable;
    }

    public Shape shape() {
        return shape;
    }

    public String purpose() {
        return purpose;
    }

    /**
     * Describes why this value may not be used, or empty when it may.
     *
     * <p>The returned text never contains the value or any part of it. A validator that quotes the
     * secret it rejected writes that secret into every log aggregator the startup output reaches,
     * which turns a configuration mistake into a disclosure.
     */
    public Optional<String> violation(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of("is not set. " + purpose);
        }
        Optional<String> shapeViolation = shape.violation(value);
        if (shapeViolation.isPresent()) {
            return shapeViolation;
        }
        if (PUBLISHED_REPOSITORY_VALUES.contains(value)) {
            return Optional.of(
                    "is set to a value published in this repository for local development or "
                            + "testing. It protects nothing. Generate a new one.");
        }
        String lowercased = value.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lowercased.contains(marker)) {
                return Optional.of(
                        "looks like a placeholder rather than a generated secret (it contains '"
                                + marker + "'). Generate a new one.");
            }
        }
        return Optional.empty();
    }

    /** How a value must be shaped for the component that reads it to be able to use it. */
    public enum Shape {

        /**
         * An opaque bearer value compared or HMAC-keyed as raw bytes. Any character set; what
         * matters is that there is enough of it.
         */
        OPAQUE_TOKEN {
            @Override
            Optional<String> violation(String value) {
                if (value.length() < MINIMUM_OPAQUE_TOKEN_LENGTH) {
                    return Optional.of("is shorter than the " + MINIMUM_OPAQUE_TOKEN_LENGTH
                            + "-character minimum for an opaque token.");
                }
                return Optional.empty();
            }

            @Override
            public String generatorHint() {
                return "openssl rand -hex 32";
            }
        },

        /**
         * Base64 that decodes to exactly an AES-256 key. The ciphers already enforce this when a
         * key is present; enforcing it here means an operator learns about a truncated paste at
         * startup rather than the first time a webhook secret needs encrypting.
         */
        AES_256_KEY {
            @Override
            Optional<String> violation(String value) {
                byte[] decoded;
                try {
                    decoded = Base64.getDecoder().decode(value.getBytes(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException notBase64) {
                    return Optional.of("is not valid Base64, so it cannot be an AES-256 key.");
                }
                if (decoded.length != AES_256_KEY_BYTES) {
                    return Optional.of("decodes to " + decoded.length + " bytes; an AES-256 key "
                            + "is exactly " + AES_256_KEY_BYTES + ".");
                }
                return Optional.empty();
            }

            @Override
            public String generatorHint() {
                return "openssl rand -base64 32";
            }
        };

        abstract Optional<String> violation(String value);

        /** The command an operator should run to produce a valid value. */
        public abstract String generatorHint();
    }
}
