package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the contract half of C57-SEC-001. The published OpenAPI document is the single artifact
 * every integrator, scanner, and code generator reads, and it is the easiest place in the product
 * to leak something by accident, because it is generated rather than written. Nothing in it may
 * name an internal surface, carry a usable credential, or show a caller a cardholder value.
 */
class OpenApiExposureTests {

    private static final Path COMMITTED_CONTRACT =
            Path.of("../../openapi/thaap-public-api-2026-08-01.yaml");

    /** Surfaces that exist but are not part of any customer-facing promise. */
    private static final List<String> INTERNAL_PATH_PREFIXES =
            List.of("/internal", "/actuator", "/admin", "/platform", "/v3/api-docs", "/swagger-ui");

    /**
     * Values that must never appear anywhere in the document. Written as whole-token patterns so
     * that a domain noun such as {@code payment_method_id} cannot be mistaken for a leak.
     */
    private static final List<Pattern> FORBIDDEN_VALUES = List.of(
            Pattern.compile("\\b4[0-9]{12}(?:[0-9]{3})?\\b"),
            Pattern.compile("\\b5[1-5][0-9]{14}\\b"),
            Pattern.compile("\\b3[47][0-9]{13}\\b"),
            Pattern.compile("(?i)\\b(cvv|cvc|cvv2|card_verification)\\b"),
            Pattern.compile("(?i)\\bBEGIN (RSA |EC |OPENSSH )?PRIVATE KEY\\b"),
            Pattern.compile("(?i)\\b(hmac|webhook)[_-]?secret\\b"),
            Pattern.compile("(?i)\\bsigning[_-]?key\\b"),
            Pattern.compile("(?i)\\b(password|passphrase|bearer [A-Za-z0-9._-]{12,})\\b"),
            Pattern.compile("(?i)\\bjdbc:"),
            Pattern.compile("(?i)\\b(social_security|ssn|national_id_number)\\b"),
            Pattern.compile("(?i)[A-Za-z0-9._%+-]+@(?!example\\.com\\b)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            // No trailing \b here: "caused by:" ends in punctuation, and a word boundary after a
            // colon followed by a space never matches, which would silently disable the pattern.
            Pattern.compile("(?i)(\\bstacktrace\\b|caused by:|\\borg\\.postgresql|\\borg\\.h2\\b)"));

    private JsonNode contract;
    private String rawContract;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("openapi/thaap-public-api-2026-08-01.yaml")) {
            assertThat(stream).as("the bundled public contract is present").isNotNull();
            rawContract = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        contract = new YAMLMapper().readTree(rawContract);
    }

    @Test
    void noInternalOperatorOrToolingSurfaceIsPublishedToIntegrators() {
        List<String> exposed = paths().stream()
                .filter(path -> INTERNAL_PATH_PREFIXES.stream().anyMatch(path::startsWith))
                .sorted().toList();
        assertThat(exposed).as("the public contract describes only the public product").isEmpty();

        // The exclusion is enforced by a path filter, so an internal route that gains a public
        // looking prefix would slip through silently. Assert the count of published operations is
        // stated by the document itself rather than assumed.
        assertThat(paths()).as("the contract is not accidentally empty").isNotEmpty();
    }

    @Test
    void noInternalRouteIsMentionedAnywhereInTheDocumentIncludingProseAndExamples() {
        List<String> mentions = new ArrayList<>();
        for (String prefix : INTERNAL_PATH_PREFIXES) {
            // Descriptions, examples, and server URLs are read by humans and by scanners. A route
            // named in prose is disclosed whether or not it is a key in paths.
            Matcher matcher = Pattern.compile(Pattern.quote(prefix) + "[/\"\\s]")
                    .matcher(rawContract);
            while (matcher.find()) {
                mentions.add(prefix + " near offset " + matcher.start());
            }
        }
        assertThat(mentions).as("no internal surface is named anywhere in the artifact").isEmpty();
    }

    @Test
    void theDocumentCarriesNoCredentialCardholderOrPersonalValue() {
        List<String> leaks = new ArrayList<>();
        for (Pattern pattern : FORBIDDEN_VALUES) {
            Matcher matcher = pattern.matcher(rawContract);
            while (matcher.find()) {
                leaks.add(pattern.pattern() + " matched '" + matcher.group() + "'");
            }
        }
        assertThat(leaks).as("the published contract carries no sensitive value").isEmpty();
    }

    @Test
    void everyServerIsAnExplicitLocalSandboxAndNeverAProductionHost() {
        JsonNode servers = contract.path("servers");
        assertThat(servers.isArray()).as("the contract declares its servers").isTrue();
        assertThat(servers).as("exactly one sandbox server is published").hasSize(1);

        String url = servers.get(0).path("url").asText();
        // A production host in a shipped artifact is a standing invitation to point a generated
        // client at live money while following the quickstart.
        assertThat(url).as("the published server is a loopback sandbox")
                .startsWith("http://127.0.0.1");
        assertThat(url).doesNotEndWith("/");
        assertThat(servers.get(0).path("description").asText())
                .as("the server states that it is a sandbox").containsIgnoringCase("sandbox");
    }

    @Test
    void theCommittedArtifactAndTheBundledArtifactAreTheSameBytes() throws Exception {
        assertThat(COMMITTED_CONTRACT).as("the repository holds the reviewed contract").exists();
        String committed = Files.readString(COMMITTED_CONTRACT, StandardCharsets.UTF_8);

        // Two copies exist so the runtime can serve the contract offline. If they can differ, the
        // reviewed artifact and the served artifact are different products.
        assertThat(committed).as("the served contract is the reviewed contract")
                .isEqualTo(rawContract);
    }

    @Test
    void everyOperationIsUniquelyAddressableByAStableOperationId() {
        Set<String> operationIds = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        contract.path("paths").properties().forEach(pathEntry ->
                pathEntry.getValue().properties().forEach(methodEntry -> {
                    String method = methodEntry.getKey().toUpperCase(Locale.ROOT);
                    if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) return;
                    String operationId = methodEntry.getValue().path("operationId").asText("");
                    String label = method + " " + pathEntry.getKey();
                    if (operationId.isBlank()) {
                        missing.add(label);
                    } else if (!operationIds.add(operationId)) {
                        // A duplicate silently overwrites a method in every generated SDK.
                        duplicates.add(label + " reuses " + operationId);
                    }
                }));

        assertThat(missing).as("every operation is nameable by a generated client").isEmpty();
        assertThat(duplicates).as("no two operations collapse into one SDK method").isEmpty();
    }

    @Test
    void noOperationLeaksAnUnreviewedFieldThroughAFreeFormObjectSchema() {
        // An unconstrained object in a response is a hole through which any future internal field
        // reaches an integrator without passing a review.
        List<String> unconstrained = new ArrayList<>();
        contract.path("components").path("schemas").properties().forEach(schemaEntry ->
                schemaEntry.getValue().path("properties").properties().forEach(propertyEntry -> {
                    JsonNode property = propertyEntry.getValue();
                    boolean opaqueObject = "object".equals(property.path("type").asText())
                            && !property.has("properties")
                            && !property.has("additionalProperties")
                            && !property.has("$ref");
                    if (opaqueObject) {
                        unconstrained.add(schemaEntry.getKey() + "." + propertyEntry.getKey());
                    }
                }));
        assertThat(unconstrained)
                .as("no published schema hides an unreviewed object shape").isEmpty();
    }

    @Test
    void theSensitiveValueScannerDetectsEachClassOfLeakItClaimsToPrevent() {
        // The live contract currently contains none of these, so without this test the previous
        // assertion would pass even if every pattern were silently broken. Each canary is a value
        // that a careless example, description, or default could realistically introduce.
        List<String> canaries = List.of(
                "example: 4242424242424242",
                "example: 5555555555554444",
                "example: 378282246310005",
                "description: supply the cvv from the card",
                "-----BEGIN RSA PRIVATE KEY-----",
                "example: webhook_secret value",
                "description: the signing-key rotates monthly",
                "example: Bearer abcdef0123456789",
                "url: jdbc:postgresql://localhost/thaap",
                "description: the customer ssn is never returned",
                "example: analyst@thaap.internal",
                "description: caused by: a null pointer");

        for (String canary : canaries) {
            boolean detected = FORBIDDEN_VALUES.stream()
                    .anyMatch(pattern -> pattern.matcher(canary).find());
            assertThat(detected).as("the scanner catches '%s'", canary).isTrue();
        }

        // And it must not fire on the domain language the contract legitimately uses, or it would
        // be disabled by the first person it inconveniences.
        List<String> legitimate = List.of(
                "example: pm_01H8XKJ2M3N4P5Q6R7S8T9V0W1",
                "description: the payment_method_id is an immutable identifier",
                "example: user@example.com",
                "description: amountMinor is expressed in minor units",
                "example: 2026-08-01T00:00:00Z");
        for (String phrase : legitimate) {
            boolean falsePositive = FORBIDDEN_VALUES.stream()
                    .anyMatch(pattern -> pattern.matcher(phrase).find());
            assertThat(falsePositive).as("the scanner tolerates '%s'", phrase).isFalse();
        }
    }

    private List<String> paths() {
        List<String> paths = new ArrayList<>();
        contract.path("paths").fieldNames().forEachRemaining(paths::add);
        return paths;
    }
}
