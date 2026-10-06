package com.thaap.payments.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the runtime secret contract equal to the configuration it claims to describe.
 *
 * <p>{@link RequiredRuntimeSecret} is a hand-written list, and a hand-written list of the things
 * that matter is the classic way this control fails: a ninth secret is added to
 * {@code application.yml}, nobody adds it here, and the gate goes on passing while enforcing seven
 * eighths of a promise. Every assertion in this class exists to make one flavour of that drift fail
 * a build rather than a deployment.
 *
 * <p>The same reasoning covers the published-value list. This repository deliberately publishes
 * working secrets so that a stranger can start the stack — which means the most likely route to a
 * production system holding a known key is somebody copying a quickstart that was written to be
 * copied. So every value the repository publishes is scraped back out of the files that publish it
 * and asserted refused.
 */
class RuntimeSecretContractDriftTests {

    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yml");
    private static final Path AWS_PROFILE_YAML = Path.of("src/main/resources/application-aws.yml");
    private static final Path TEST_PROFILE_YAML = Path.of("src/test/resources/application-test.yml");
    private static final Path COMPOSE = Path.of("../../infra/docker/compose.yml");
    private static final List<Path> QUICKSTARTS = List.of(
            Path.of("../../README.md"), Path.of("README.md"));

    /**
     * A configuration leaf whose name says it holds a secret.
     *
     * <p>Singular on purpose. {@code previous-hash-peppers} and {@code secret-decryption-keys} are
     * rotation sets, and an empty rotation set is a valid state — it is what every deployment that
     * has never rotated looks like. Requiring them would make a normal system look broken.
     */
    private static final Pattern SECRET_LEAF =
            Pattern.compile("^(?:.*-)?(?:token|pepper|encryption-key)$");

    private static final Pattern COMPOSE_DEFAULT = Pattern.compile(
            "(THAAP_[A-Z0-9_]+):\\s*\\$\\{THAAP_[A-Z0-9_]+:-([^}]*)}");

    private static final Pattern SHELL_ASSIGNMENT = Pattern.compile(
            "(THAAP_[A-Z0-9_]+)=\"?([^\"\\s]+)\"?");

    private final YAMLMapper yaml = new YAMLMapper();

    @Test
    void theContractCoversEverySecretShapedValueTheApplicationReads() throws Exception {
        Set<String> declared = new TreeSet<>();
        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            declared.add(secret.property());
        }

        Set<String> configured = new TreeSet<>();
        collectPlaceholders(yaml.readTree(Files.readString(APPLICATION_YAML, StandardCharsets.UTF_8)),
                "", configured);
        configured.removeIf(property -> !SECRET_LEAF.matcher(leafOf(property)).matches());

        assertThat(configured)
                .as("a secret the application reads but the contract does not enforce is a "
                        + "secret nothing enforces")
                .isEqualTo(declared);
    }

    @Test
    void everySecretResolvesFromTheEnvironmentVariableTheContractNames() throws Exception {
        JsonNode application =
                yaml.readTree(Files.readString(APPLICATION_YAML, StandardCharsets.UTF_8));

        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            // The error message tells an operator to set an environment variable. If the
            // application reads a different one, the message sends them somewhere that does
            // nothing, which is worse than saying nothing at all.
            assertThat(valueAt(application, secret.property()))
                    .as("%s", secret.property())
                    .isEqualTo("${" + secret.environmentVariable() + ":}");
        }
    }

    @Test
    void theDeployedProfileTurnsTheContractOn() throws Exception {
        JsonNode awsProfile =
                yaml.readTree(Files.readString(AWS_PROFILE_YAML, StandardCharsets.UTF_8));

        assertThat(valueAt(awsProfile, RuntimeSecretContractGate.ENFORCEMENT_PROPERTY))
                .as("the whole reason the aws profile exists")
                .isEqualTo("true");
    }

    @Test
    void theDefaultProfileLeavesTheContractOff() throws Exception {
        // A laptop must keep starting without a secrets ceremony. This is the reason the check is
        // opt-in rather than unconditional, and it is worth asserting rather than assuming: an
        // enforcement default that flipped would break every developer and every test at once.
        JsonNode application =
                yaml.readTree(Files.readString(APPLICATION_YAML, StandardCharsets.UTF_8));

        assertThat(valueAt(application, RuntimeSecretContractGate.ENFORCEMENT_PROPERTY)).isNull();
    }

    @Test
    void everySecretThisRepositoryPublishesIsRefused() throws Exception {
        Map<RequiredRuntimeSecret, Set<String>> published = publishedValues();

        assertThat(published)
                .as("the scrape found nothing, which means it is no longer reading the files "
                        + "that publish these values")
                .isNotEmpty();

        List<String> accepted = new ArrayList<>();
        for (Map.Entry<RequiredRuntimeSecret, Set<String>> entry : published.entrySet()) {
            for (String value : entry.getValue()) {
                if (entry.getKey().violation(value).isEmpty()) {
                    accepted.add(entry.getKey().environmentVariable() + " would accept a value "
                            + "this repository publishes");
                }
            }
        }

        assertThat(accepted)
                .as("every value committed to this repository must be refused in a deployment")
                .isEmpty();
    }

    @Test
    void theQuickstartAndTheStackTogetherPublishASecretForEveryVariable() throws Exception {
        // Confirms the scrape above is actually reaching all eight rather than quietly matching
        // two and reporting success. A drift lock that stops finding its inputs stops being one.
        assertThat(publishedValues().keySet())
                .containsExactlyInAnyOrder(RequiredRuntimeSecret.values());
    }

    /** Every literal secret value this repository commits, by the variable it is committed for. */
    private Map<RequiredRuntimeSecret, Set<String>> publishedValues() throws IOException {
        Map<String, RequiredRuntimeSecret> byEnvironmentVariable = new LinkedHashMap<>();
        Map<String, RequiredRuntimeSecret> byProperty = new LinkedHashMap<>();
        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            byEnvironmentVariable.put(secret.environmentVariable(), secret);
            byProperty.put(secret.property(), secret);
        }

        Map<RequiredRuntimeSecret, Set<String>> published = new LinkedHashMap<>();

        // The local development stack.
        Matcher composeDefaults =
                COMPOSE_DEFAULT.matcher(Files.readString(COMPOSE, StandardCharsets.UTF_8));
        while (composeDefaults.find()) {
            record(published, byEnvironmentVariable.get(composeDefaults.group(1)),
                    composeDefaults.group(2));
        }

        // The documented quickstart, which is written to be copied.
        for (Path quickstart : QUICKSTARTS) {
            Matcher assignments =
                    SHELL_ASSIGNMENT.matcher(Files.readString(quickstart, StandardCharsets.UTF_8));
            while (assignments.find()) {
                String value = assignments.group(2);
                if (value.contains("$(") || value.startsWith("${")) {
                    // A generated value rather than a literal. Nothing to refuse.
                    continue;
                }
                record(published, byEnvironmentVariable.get(assignments.group(1)), value);
            }
        }

        // The suite's own fixtures, which are the values most likely to be reached for by someone
        // who wants a stack to start and does not want to think about it.
        JsonNode testProfile =
                yaml.readTree(Files.readString(TEST_PROFILE_YAML, StandardCharsets.UTF_8));
        for (Map.Entry<String, RequiredRuntimeSecret> entry : byProperty.entrySet()) {
            record(published, entry.getValue(), valueAt(testProfile, entry.getKey()));
        }

        return published;
    }

    private void record(Map<RequiredRuntimeSecret, Set<String>> published,
            RequiredRuntimeSecret secret, String value) {
        if (secret == null || value == null || value.isBlank()) {
            return;
        }
        published.computeIfAbsent(secret, key -> new LinkedHashSet<>()).add(value);
    }

    private void collectPlaceholders(JsonNode node, String path, Set<String> placeholders) {
        if (node.isObject()) {
            node.properties().forEach(field -> collectPlaceholders(field.getValue(),
                    path.isEmpty() ? field.getKey() : path + "." + field.getKey(), placeholders));
            return;
        }
        if (node.isTextual() && node.asText().startsWith("${THAAP_")) {
            placeholders.add(path);
        }
    }

    private String leafOf(String property) {
        return property.substring(property.lastIndexOf('.') + 1);
    }

    private String valueAt(JsonNode root, String property) {
        JsonNode node = root;
        for (String segment : property.split("\\.")) {
            node = node.path(segment);
        }
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
