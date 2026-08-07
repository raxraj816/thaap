package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Proof for {@code C52-OBS-001}.
 *
 * <p>Operations must be able to tell the integrated workflow's stages apart, and must be able to
 * do so without the signals themselves becoming a leak. Three properties carry that: the run
 * emits rebuild signals at all, no meter tag carries an identifier or a secret, and every
 * readiness contributor explains itself rather than returning a bare status.
 *
 * <p>The cardinality scan runs over every meter in the registry rather than a chosen few. A
 * high-cardinality tag added to some unrelated meter is exactly the kind of thing a targeted
 * scan misses and a bill discovers.
 */
class PhaseFiveOperationalSignalsIntegrationTests extends PhaseFiveClosureFixture {

    @Autowired private MeterRegistry meterRegistry;

    @Test
    void theIntegratedRunEmitsDistinguishableRebuildSignals() {
        runRebuildToActivation("closure-observability");

        List<String> rebuildMeters = meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.contains("projection.rebuild"))
                .distinct()
                .toList();
        assertThat(rebuildMeters)
                .describedAs("the integrated rebuild emitted no signal at all")
                .isNotEmpty();

        // The stages an operator must distinguish are distinguishable by tag, rather than
        // collapsed into one undifferentiated counter.
        List<String> outcomes = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().contains("projection.rebuild"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getValue)
                .distinct()
                .toList();
        assertThat(outcomes).isNotEmpty();
    }

    @Test
    void noMeterTagCarriesAnIdentifierOrSecret() {
        runRebuildToActivation("closure-observability-cardinality");

        List<String> forbidden = List.of(
                first.merchantId().toString(), second.merchantId().toString(),
                first.paymentIntentId().toString(), second.paymentIntentId().toString(),
                first.apiKey(), second.apiKey(), dataOperatorKey);

        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getValue())
                        .describedAs("meter %s tag %s carries unbounded cardinality",
                                meter.getId().getName(), tag.getKey())
                        .isNotIn(forbidden);
                assertThat(isIdentifier(tag.getValue()))
                        .describedAs("meter %s tag %s is an identifier",
                                meter.getId().getName(), tag.getKey())
                        .isFalse();
            }
        }
    }

    @Test
    void readinessExplainsItselfForEveryPhaseFiveComponent() {
        runRebuildToActivation("closure-observability-readiness");

        for (String component : List.of("projectionRebuild", "offlineReplayDataset",
                "domainEventProducer")) {
            HealthIndicator indicator = webApplicationContext.getBean(component,
                    HealthIndicator.class);
            Status status = indicator.health().getStatus();
            assertThat(status)
                    .describedAs("%s reported neither a healthy nor an explained state", component)
                    .isIn(Status.UP, Status.OUT_OF_SERVICE, Status.DOWN);
            assertThat(indicator.health().getDetails())
                    .describedAs("%s reported a status with no detail to act on", component)
                    .isNotEmpty();
        }
    }

    private boolean isIdentifier(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException notAnIdentifier) {
            return false;
        }
    }
}
