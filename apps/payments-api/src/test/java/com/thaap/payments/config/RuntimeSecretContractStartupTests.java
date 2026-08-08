package com.thaap.payments.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.thaap.payments.PaymentsApiApplication;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the refusal against a real {@code SpringApplication} rather than against the validator.
 *
 * <p>The unit tests prove the rules. They cannot prove the rules are wired to anything: an
 * {@code EnvironmentPostProcessor} that is never registered is a class with excellent test coverage
 * and no effect on any deployment, which is the shape of escaped finding this repository keeps
 * recording. So these tests start the application.
 *
 * <p>The third test is the one that matters most. It points the datasource at an address nothing is
 * listening on and still expects the failure to be the secret contract — the only way to
 * demonstrate that the refusal happens before the instance reaches the production database or takes
 * the Flyway migration lock.
 */
class RuntimeSecretContractStartupTests {

    /**
     * A database of this class's own. The suite's shared in-memory database is migrated and read by
     * dozens of cached contexts, and an instance started here has no business touching it.
     */
    private static final String ISOLATED_DATABASE = "jdbc:h2:mem:runtime_secret_contract"
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    /** Loopback port 1: reserved, unbindable, and reachable by nothing. */
    private static final String UNREACHABLE_DATABASE =
            "jdbc:postgresql://127.0.0.1:1/thaap_payments_nothing_is_here";

    @Test
    void refusesToStartWhenASecretIsMissing() {
        Throwable refusal = catchThrowable(() -> start(without(
                deployableArguments(ISOLATED_DATABASE),
                RequiredRuntimeSecret.REVIEW_NOTE_ENCRYPTION_KEY)));

        assertThat(refusal).isNotNull();
        assertThat(rootCauseMessage(refusal))
                .contains("refuses to start")
                .contains("THAAP_REVIEW_NOTE_ENCRYPTION_KEY")
                .as("only the secret that is actually wrong may be named")
                .doesNotContain("THAAP_ADMIN_BOOTSTRAP_TOKEN");
    }

    @Test
    void startsWhenEverySecretIsDeployable() {
        try (ConfigurableApplicationContext context =
                start(deployableArguments(ISOLATED_DATABASE))) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getProperty(
                    RuntimeSecretContractGate.ENFORCEMENT_PROPERTY))
                    .isEqualTo("true");
        }
    }

    @Test
    void refusesBeforeItOpensADatabaseConnection() {
        // If this ran as a bean instead, the failure here would be a connection or Flyway error,
        // because the datasource and the migration lock come first — meaning the instance had
        // already reached the production database before deciding it must not run.
        Throwable refusal = catchThrowable(() -> start(without(
                deployableArguments(UNREACHABLE_DATABASE), RequiredRuntimeSecret.HASH_PEPPER)));

        assertThat(rootCauseMessage(refusal))
                .as("the refusal must be about the secret, not about a database it never dialled")
                .contains("THAAP_HASH_PEPPER")
                .doesNotContain("Connection")
                .doesNotContain("flyway");
    }

    private ConfigurableApplicationContext start(List<String> arguments) {
        return new SpringApplicationBuilder(PaymentsApiApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    private List<String> deployableArguments(String datasourceUrl) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + datasourceUrl,
                "--" + RuntimeSecretContractGate.ENFORCEMENT_PROPERTY + "=true"));
        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            arguments.add("--" + secret.property() + "=" + deployableValue(secret));
        }
        return arguments;
    }

    /**
     * Drops one secret's override so the property falls back to the empty default
     * {@code application.yml} gives it — the real production accident is not an absent key, it is
     * a key nobody set.
     */
    private List<String> without(List<String> arguments, RequiredRuntimeSecret omitted) {
        List<String> remaining = new ArrayList<>();
        for (String argument : arguments) {
            if (!argument.startsWith("--" + omitted.property() + "=")) {
                remaining.add(argument);
            }
        }
        return remaining;
    }

    private String deployableValue(RequiredRuntimeSecret secret) {
        return switch (secret.shape()) {
            case OPAQUE_TOKEN -> "9f3c" + "0123456789abcdef".repeat(2) + secret.ordinal();
            case AES_256_KEY -> {
                byte[] key = new byte[RequiredRuntimeSecret.AES_256_KEY_BYTES];
                System.arraycopy("0123456789abcdef0123456789abcde"
                        .getBytes(StandardCharsets.UTF_8), 0, key, 0, 31);
                key[key.length - 1] = (byte) ('A' + secret.ordinal());
                yield Base64.getEncoder().encodeToString(key);
            }
        };
    }

    private String rootCauseMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? "" : String.valueOf(cause.getMessage());
    }
}
