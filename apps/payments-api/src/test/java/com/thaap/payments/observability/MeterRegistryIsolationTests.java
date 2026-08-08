package com.thaap.payments.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the boundary a whole test suite shares without seeing it: one Micrometer registry.
 *
 * <p>Spring caches one application context — and therefore one {@link MeterRegistry} bean — per
 * distinct test configuration, and hands that same instance to every class whose configuration
 * matches. A class that empties or prunes the registry in setup is not resetting its own state; it
 * is reaching into the state of every other class in the cache group. Nothing reports this. Meters
 * that are re-registered on each use silently reappear, so the damage is invisible except to a
 * meter registered exactly once — and the failure then depends on which class ran first, which is
 * decided by the filesystem.
 *
 * <p>That is not hypothetical. {@code PaymentInvestigationObservabilityTests} removed every
 * {@code thaap.decision_intelligence.*} meter in its {@code @BeforeEach} while sharing a context
 * with {@code ReviewBulkBacklogReadinessTests}, which asserts on the two bulk-backlog gauges —
 * the entire detection surface for a bulk run whose worker died, since nothing else reports it.
 * In the orderings that ran the investigation class first, the gauges were gone for the life of
 * that context and the readiness assertions read an absent meter. See {@code ER-0101}.
 */
class MeterRegistryIsolationTests {

    /**
     * Marks a test class as owning its registry. It has no binder and no runtime effect; its only
     * job is to give the class a distinct Spring context cache key.
     */
    private static final String EXCLUSIVE_REGISTRY_PROPERTY = "thaap.test.exclusive-meter-registry";

    /** {@code registry.clear()}, {@code registry.remove(..)} and {@code registry::remove}. */
    private static final Pattern REGISTRY_MUTATION =
            Pattern.compile("\\b\\w*[Rr]egistry\\s*(\\.\\s*(clear|remove)\\s*\\(|::\\s*remove)");

    @Test
    void noSharedSpringContextTestRemovesMetersFromTheRegistryItShares() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path source : testSourceFiles()) {
            // This file quotes the patterns it looks for, so scanning it would flag the gate.
            if (source.getFileName().toString().equals("MeterRegistryIsolationTests.java")) {
                continue;
            }
            String code = withoutComments(Files.readString(source));
            boolean springContextTest = code.contains("@SpringBootTest");
            boolean touchesMeterRegistry = code.contains("MeterRegistry");
            boolean ownsItsRegistry = code.contains(EXCLUSIVE_REGISTRY_PROPERTY);
            if (springContextTest && touchesMeterRegistry && !ownsItsRegistry
                    && REGISTRY_MUTATION.matcher(code).find()) {
                offenders.add(source.toString());
            }
        }

        assertThat(offenders)
                .as("these classes empty or prune a MeterRegistry that Spring shares with every "
                        + "other test of the same configuration; declare \"%s=<name>\" in "
                        + "@SpringBootTest(properties = ...) so the class gets a registry of its own",
                        EXCLUSIVE_REGISTRY_PROPERTY)
                .isEmpty();
    }

    /**
     * The reason the gauges could not survive the sweep, stated as behaviour rather than history.
     *
     * <p>Isolation is the fix; this is the second line. Every other meter in
     * {@link DecisionIntelligenceMetrics} is re-registered on each use and therefore heals itself,
     * and these two must do the same, because a signal whose only job is to report a silent failure
     * must not be able to fail silently.
     */
    @Test
    void theBacklogGaugesComeBackAfterSomethingRemovesThemFromTheRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionIntelligenceMetrics metrics = new DecisionIntelligenceMetrics(registry);
        metrics.bulkBacklog(4, Duration.ofMinutes(30));

        assertThat(registry.find("thaap.decision_intelligence.bulk.backlog").gauge()).isNotNull();

        // Exactly what the neighbouring test class did to the shared registry.
        List<Meter> swept = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("thaap.decision_intelligence"))
                .toList();
        assertThat(swept).as("the sweep must actually find the meters it is meant to remove")
                .isNotEmpty();
        swept.forEach(registry::remove);
        assertThat(registry.find("thaap.decision_intelligence.bulk.backlog").gauge()).isNull();

        metrics.bulkBacklog(7, Duration.ofHours(2));

        assertThat(registry.get("thaap.decision_intelligence.bulk.backlog").gauge().value())
                .as("a gauge registered only once is gone for the life of the process once "
                        + "anything removes it")
                .isEqualTo(7d);
        assertThat(registry.get("thaap.decision_intelligence.bulk.oldest_age_seconds").gauge()
                .value()).isEqualTo(7200d);
    }

    private static List<Path> testSourceFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/test/java"))) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
        }
        assertThat(files).as("the source scan must actually find sources").isNotEmpty();
        return files;
    }

    /** Strips comments so prose naming the forbidden calls does not fail the scan. */
    private static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
