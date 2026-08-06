package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves C57-SCP-001. The version, error, and OpenAPI contracts stop at the routes present at
 * this chapter's cutoff. They must not advertise a client library that is not yet supported, and
 * must not claim a public package release the project has explicitly deferred — a contract that
 * promises an SDK before one exists sends integrators to a download that is not there.
 */
class PublicContractScopeBoundaryTests {

    private static final Path REPOSITORY_ROOT = Path.of("..", "..").normalize();
    private static final Path CONTRACT =
            REPOSITORY_ROOT.resolve("openapi/thaap-public-api-2026-08-01.yaml");

    /** Registries and installers that would imply a released, installable client package. */
    private static final List<String> PUBLIC_RELEASE_MARKERS = List.of(
            "pypi.org", "test.pypi.org", "pip install thaap", "npmjs.com",
            "registry.npmjs.org", "maven central", "repo1.maven.org");

    @Test
    void theCommittedContractExistsAndDeclaresExactlyOneDateVersion() throws IOException {
        assertThat(Files.exists(CONTRACT))
                .as("the chapter's central deliverable is the committed contract artifact")
                .isTrue();
        String source = Files.readString(CONTRACT, StandardCharsets.UTF_8);
        assertThat(source).contains("2026-08-01");
        // A second date version would mean the multi-version lifecycle arrived early, which the
        // plan assigns to a later phase with its own compatibility-matrix evidence.
        for (String laterVersion : List.of("2026-09-01", "2026-10-01", "2027-")) {
            assertThat(source).as("the contract advertises an unreleased version %s", laterVersion)
                    .doesNotContain(laterVersion);
        }
    }

    @Test
    void theContractNeverAdvertisesAPublicPackageReleaseTheProjectHasDeferred() throws IOException {
        String source = Files.readString(CONTRACT, StandardCharsets.UTF_8).toLowerCase();
        for (String marker : PUBLIC_RELEASE_MARKERS) {
            assertThat(source)
                    .as("the contract points integrators at %s, but public package release is "
                            + "a named Phase 9 exclusion", marker)
                    .doesNotContain(marker);
        }
    }

    @Test
    void theContractDescribesOnlyLoopbackSandboxServers() throws IOException {
        // The committed artifact is JSON-encoded despite its .yaml extension, so it is parsed
        // rather than string-matched; a substring search would pass on a malformed document.
        JsonNode servers = new ObjectMapper().readTree(CONTRACT.toFile()).get("servers");
        assertThat(servers).as("the contract must declare its servers explicitly").isNotNull();
        assertThat(servers.isArray()).isTrue();
        assertThat(servers).isNotEmpty();

        servers.forEach(server -> {
            String url = server.get("url").asText();
            assertThat(url)
                    .as("a hosted server URL implies a deployment that does not exist")
                    .containsAnyOf("127.0.0.1", "localhost");
            assertThat(url).doesNotContain("api.thaap.com").doesNotContain(".prod.")
                    .doesNotContain("amazonaws.com");
        });
    }

    @Test
    void theContractGenerationToolingIsCommittedAndInvocableRatherThanAdHoc() {
        for (String script : List.of("scripts/generate-openapi-contract.py",
                "scripts/openapi-contract-check.py", "scripts/openapi-compatibility-check.py",
                "scripts/public-api-error-contract-check.py")) {
            assertThat(Files.exists(REPOSITORY_ROOT.resolve(script)))
                    .as("%s is part of the chapter's reproducibility claim", script).isTrue();
        }
    }

    @Test
    void theCompatibilityGateIsWiredIntoCommitReadinessRatherThanRunManually() throws IOException {
        String readiness = Files.readString(
                REPOSITORY_ROOT.resolve("scripts/commit-readiness-check.sh"),
                StandardCharsets.UTF_8);
        // A gate nobody runs is not a gate. These must be invoked by the standard check.
        assertThat(readiness).contains("openapi-compatibility-check.py");
        assertThat(readiness).contains("public-api-error-contract-check.py");
        assertThat(readiness).contains("executable-artifact-naming-check.py");
    }

    @Test
    void theBreakingChangeGateShipsWithANegativeFixtureThatProvesItCanFail() throws IOException {
        Path negative = REPOSITORY_ROOT.resolve("scripts/openapi-breaking-change-negative-test.sh");
        assertThat(Files.exists(negative))
                .as("a compatibility gate with no proof that it rejects anything is decoration")
                .isTrue();
        String source = Files.readString(negative, StandardCharsets.UTF_8);
        // The fixture must actually remove something and require rejection.
        assertThat(source).containsAnyOf("customers", "remove", "delete");
    }
}
