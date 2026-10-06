package com.thaap.payments.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves that a running instance can state which artifact it is and which commit produced it.
 *
 * <p>The failure this exists to prevent is mundane and expensive: an incident where the only
 * available answer to "what is deployed?" is somebody's memory of what they last pushed. Reading
 * the version from a tag on the task definition is not the same claim — a tag is what the deployer
 * intended, and the process answering requests is the only thing that knows what it actually is.
 *
 * <p>The identity is asserted at three boundaries because a single one of them is not enough. The
 * jar must carry the properties; the actuator endpoint must serve them; and the endpoint must be
 * exposed over HTTP, since an operator with a URL and no shell is the reader this promise is for.
 * The remaining boundary — that a released <em>image</em> reports the commit it was built from
 * rather than {@code unknown} — cannot be reached from a unit test, because it depends on how the
 * image was built. {@code scripts/release-identity-check.sh} builds one and asks it.
 */
@ActiveProfiles("test")
@SpringBootTest
class ReleaseIdentityContractTests {

    private static final Path POM = Path.of("pom.xml");
    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yml");
    private static final Path CHANGELOG = Path.of("../../CHANGELOG.md");

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private InfoEndpoint infoEndpoint;

    @Test
    void theArtifactCarriesTheVersionAndCommitItWasBuiltFrom() {
        assertThat(buildProperties.getVersion()).isEqualTo(declaredProjectVersion());
        assertThat(buildProperties.getArtifact()).isEqualTo("payments-api");
        assertThat(buildProperties.get("commit"))
                .as("build-info must carry a commit key even when its value is the honest "
                        + "`unknown` of a developer build")
                .isNotNull();
    }

    @Test
    void theInfoEndpointServesTheVersionAndCommitToACallerWithNoShellAccess() {
        Object build = infoEndpoint.info().get("build");
        assertThat(build).isInstanceOf(Map.class);

        Map<?, ?> buildInfo = (Map<?, ?>) build;
        assertThat(buildInfo.get("version")).isEqualTo(declaredProjectVersion());
        assertThat(buildInfo.get("commit")).isNotNull();
    }

    @Test
    void buildInfoOmitsTheBuildTimeSoOneCommitProducesOneArtifact() {
        // A build timestamp changes on every rebuild of the same source, which would make the
        // artifact differ from itself and quietly retire the promise that one commit produces
        // one image.
        assertThat(buildProperties.getTime())
                .as("build.time must be excluded; it is the only property here that is not a "
                        + "function of the source")
                .isNull();
    }

    @Test
    void theInfoEndpointIsExposedOverHttp() throws Exception {
        String applicationYaml = Files.readString(APPLICATION_YAML, StandardCharsets.UTF_8);
        assertThat(applicationYaml).contains("include: health,info,metrics");
        assertThat(applicationYaml).contains("build:\n      enabled: true");
    }

    @Test
    void theVersionHasExactlyOneSource() throws Exception {
        // `info.app.version` used to restate the pom version in application.yml, and by the time
        // anyone looked it said 0.0.1-SNAPSHOT about an artifact that was something else. Two
        // places to write one fact is two places for it to be wrong.
        String applicationYaml = Files.readString(APPLICATION_YAML, StandardCharsets.UTF_8);
        assertThat(applicationYaml)
                .as("the artifact version must reach /actuator/info through build-info only")
                .doesNotContain("version: 0.0.1-SNAPSHOT")
                .doesNotContain("version: " + declaredProjectVersion());
    }

    @Test
    void everyReleasedVersionHasAChangelogEntry() throws Exception {
        String changelog = Files.readString(CHANGELOG, StandardCharsets.UTF_8);
        assertThat(changelog)
                .as("a version with no changelog entry is a deployment nobody can describe")
                .contains("## [" + declaredProjectVersion() + "]");
    }

    @Test
    void theChangelogStatesTheSimulatorBoundaryWhereverTheVersionIsPublished() throws Exception {
        // 1.0.0 is a complete, proven, deployed simulator. It is not a system licensed to move
        // real money, and a major version is exactly the signal a reader would take that as.
        String changelog = Files.readString(CHANGELOG, StandardCharsets.UTF_8);
        assertThat(changelog.toLowerCase(java.util.Locale.ROOT))
                .contains("no real card data")
                .contains("not pci");
    }

    /**
     * Reads the version from the pom rather than trusting the artifact under test to agree with
     * it — the whole question here is whether those two ever diverge.
     */
    private String declaredProjectVersion() {
        try {
            String pom = Files.readString(POM, StandardCharsets.UTF_8);
            int parentEnd = pom.indexOf("</parent>");
            int versionStart = pom.indexOf("<version>", parentEnd);
            int versionEnd = pom.indexOf("</version>", versionStart);
            return pom.substring(versionStart + "<version>".length(), versionEnd).trim();
        } catch (Exception unreadable) {
            throw new IllegalStateException("pom.xml could not be read", unreadable);
        }
    }
}
