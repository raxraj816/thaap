package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C57-OBS-001. Version resolution is the one place every public request passes through, so
 * it is also the easiest place to accidentally build an unbounded time series: the requested
 * version is attacker-controlled free text. Each resolution outcome must be countable, and the
 * value that produced it must never become a dimension.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class DeveloperContractObservabilityTests {

    private static final String VERSION_METER = "thaap.decision_intelligence.api_version";

    /** Dimensions that would make one caller, request, or input its own time series. */
    private static final Set<String> PROHIBITED_TAG_KEYS = Set.of(
            "version", "requested", "requestedVersion", "input", "value", "merchant",
            "merchantId", "operator", "path", "uri", "url", "credential", "apiKey",
            "idempotencyKey", "requestId", "message", "exception");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private MeterRegistry meterRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
    }

    @Test
    void everyVersionResolutionOutcomeIsCountedUnderItsOwnClosedName() throws Exception {
        double absentBefore = versionCount("ABSENT");
        double exactBefore = versionCount("EXACT");
        double malformedBefore = versionCount("MALFORMED");
        double unsupportedBefore = versionCount("UNSUPPORTED");

        mockMvc.perform(get("/developer/api-versions"));
        mockMvc.perform(get("/developer/api-versions").header("Thaap-Version", "2026-08-01"));
        mockMvc.perform(get("/developer/api-versions").header("Thaap-Version", "not-a-date"));
        mockMvc.perform(get("/developer/api-versions").header("Thaap-Version", "2030-01-01"));

        assertThat(versionCount("ABSENT")).isEqualTo(absentBefore + 1);
        assertThat(versionCount("EXACT")).isEqualTo(exactBefore + 1);
        assertThat(versionCount("MALFORMED")).isEqualTo(malformedBefore + 1);
        assertThat(versionCount("UNSUPPORTED"))
                .as("an unsupported future date is a distinct operational signal from a "
                        + "malformed one; collapsing them hides a retired-version rollout")
                .isEqualTo(unsupportedBefore + 1);
    }

    @Test
    void aHostileVersionValueNeverBecomesAMetricDimensionOrItsOwnTimeSeries() throws Exception {
        // Establish the MALFORMED series first, so the loop below measures growth caused by
        // distinct hostile values rather than the ordinary creation of the outcome's own series.
        mockMvc.perform(get("/developer/api-versions").header("Thaap-Version", "seed-malformed"));
        int seriesBefore = versionSeriesCount();
        double malformedBefore = versionCount("MALFORMED");

        // Every one of these also resolves to MALFORMED. If the requested value reached the
        // registry, each would mint its own series and this loop would be a memory attack.
        for (int index = 0; index < 25; index++) {
            mockMvc.perform(get("/developer/api-versions")
                    .header("Thaap-Version", "hostile-" + UUID.randomUUID()));
        }
        mockMvc.perform(get("/developer/api-versions")
                .header("Thaap-Version", "'; DROP TABLE payment_intents; --"));

        assertThat(versionSeriesCount())
                .as("26 further distinct hostile inputs must not create a single new time series")
                .isEqualTo(seriesBefore);
        assertThat(versionCount("MALFORMED"))
                .as("they must all land on the one MALFORMED counter instead")
                .isEqualTo(malformedBefore + 26);
        assertThat(versionSeriesCount())
                .as("the outcome vocabulary is closed at four values")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void noVersionMeterCarriesATagKeyThatCouldIdentifyACallerOrRequest() throws Exception {
        mockMvc.perform(get("/developer/api-versions"));

        List<Meter> meters = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(VERSION_METER)).toList();
        assertThat(meters).as("the scan is vacuous without a registered version meter").isNotEmpty();

        for (Meter meter : meters) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(PROHIBITED_TAG_KEYS)
                        .as("%s carries the identifying tag key %s", VERSION_METER, tag.getKey())
                        .doesNotContain(tag.getKey());
            }
            List<String> keys = meter.getId().getTags().stream().map(Tag::getKey).toList();
            assertThat(keys)
                    .as("the version meter is dimensioned by outcome and nothing else")
                    .containsExactly("outcome");
        }
    }

    @Test
    void theRecordedOutcomeValuesStayInsideTheClosedVocabulary() throws Exception {
        // Drive one request of each shape so the registry holds something to inspect.
        mockMvc.perform(get("/developer/api-versions"));
        mockMvc.perform(get("/developer/api-versions").header("Thaap-Version", "2026-08-01"));
        mockMvc.perform(get("/developer/api-versions").header("Thaap-Version", "nope"));

        List<String> outcomes = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(VERSION_METER))
                .map(meter -> meter.getId().getTag("outcome")).toList();
        assertThat(outcomes).isNotEmpty();
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome)
                .isIn("ABSENT", "EXACT", "MALFORMED", "UNSUPPORTED"));
    }

    private double versionCount(String outcome) {
        Counter counter = meterRegistry.find(VERSION_METER).tag("outcome", outcome).counter();
        return counter == null ? 0d : counter.count();
    }

    private int versionSeriesCount() {
        return (int) meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(VERSION_METER)).count();
    }
}
