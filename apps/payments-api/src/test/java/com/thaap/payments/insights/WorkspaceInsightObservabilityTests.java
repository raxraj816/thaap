package com.thaap.payments.insights;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.observability.DecisionIntelligenceObservabilityFilter;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C56-OBS-001. Insight requests carry attacker- and tenant-controlled inputs — a range, an
 * origin policy, a merchant, an operator — and every one of them is a candidate dimension. The
 * meter must distinguish the three insight types and their outcomes while carrying none of those
 * values, because a per-merchant or per-range series turns an aggregate API into a memory attack.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class WorkspaceInsightObservabilityTests {

    private static final String REQUEST_TIMER = "thaap.decision_intelligence.requests";

    /** Tag keys that would make one caller, range, or resource its own time series. */
    private static final Set<String> PROHIBITED_TAG_KEYS = Set.of(
            "merchant", "merchantId", "tenant", "operator", "operatorId", "actor",
            "range", "from", "to", "originPolicy", "generation", "generationId",
            "currency", "amount", "payment", "review", "cursor", "credential",
            "path", "uri", "url", "message", "exception", "requestId");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private DecisionIntelligenceObservabilityFilter observabilityFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private Clock clock;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;
    private Operator analyst;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter, observabilityFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("insight-observability");
        manager = scenario.operator(merchant, "insight-obs-manager", "RISK_MANAGER");
        analyst = scenario.operator(merchant, "insight-obs-analyst", "ANALYST");
    }

    @Test
    void eachInsightTypeIsCountedUnderItsOwnOperationName() throws Exception {
        double decisionBefore = count("DECISION_INSIGHTS", "SUCCEEDED");
        double reviewBefore = count("REVIEW_INSIGHTS", "SUCCEEDED");
        double operatorBefore = count("OPERATOR_INSIGHTS", "SUCCEEDED");

        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        mockMvc.perform(get("/risk/workspace/insights/review-operations")
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        mockMvc.perform(get("/risk/workspace/insights/operators")
                .header(RISK_OPERATOR_HEADER, manager.credential()));

        // Collapsing the three into one series would hide which surface is slow or failing.
        assertThat(count("DECISION_INSIGHTS", "SUCCEEDED")).isEqualTo(decisionBefore + 1);
        assertThat(count("REVIEW_INSIGHTS", "SUCCEEDED")).isEqualTo(reviewBefore + 1);
        assertThat(count("OPERATOR_INSIGHTS", "SUCCEEDED")).isEqualTo(operatorBefore + 1);
    }

    @Test
    void aRefusedRangeAndADeniedRoleAreDistinctOutcomesRatherThanGenericFailures()
            throws Exception {
        double invalidBefore = count("DECISION_INSIGHTS", "INVALID");
        double deniedBefore = count("OPERATOR_INSIGHTS", "DENIED");

        Instant to = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .param("from", to.minus(32, ChronoUnit.DAYS).toString())
                .param("to", to.toString())
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        mockMvc.perform(get("/risk/workspace/insights/operators")
                .header(RISK_OPERATOR_HEADER, analyst.credential()));

        // A bad range is a client mistake; a denied role is an authorization event. An operator
        // paging on one must not be woken by the other.
        assertThat(count("DECISION_INSIGHTS", "INVALID")).isEqualTo(invalidBefore + 1);
        assertThat(count("OPERATOR_INSIGHTS", "DENIED")).isEqualTo(deniedBefore + 1);
    }

    @Test
    void manyDistinctRangesAndOriginPoliciesCreateNoAdditionalTimeSeries() throws Exception {
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        int seriesBefore = insightSeriesCount();

        Instant startOfToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int days = 1; days <= 20; days++) {
            mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                    .param("from", startOfToday.minus(days, ChronoUnit.DAYS).toString())
                    .param("to", startOfToday.toString())
                    .param("originPolicy", days % 2 == 0 ? "LIVE_ONLY" : "SYNTHETIC_ONLY")
                    .header(RISK_OPERATOR_HEADER, manager.credential()));
        }

        assertThat(insightSeriesCount())
                .as("20 distinct ranges across two origin policies must not multiply the series")
                .isEqualTo(seriesBefore);
    }

    @Test
    void twoTenantsShareOneSeriesRatherThanOnePerMerchant() throws Exception {
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        int seriesBefore = insightSeriesCount();

        Merchant neighbour = scenario.merchant("insight-observability-neighbour");
        Operator neighbourManager = scenario.operator(neighbour, "neighbour-manager",
                "RISK_MANAGER");
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, neighbourManager.credential()));

        assertThat(insightSeriesCount())
                .as("a second tenant must not mint its own series, or the registry grows with "
                        + "the customer list")
                .isEqualTo(seriesBefore);
    }

    @Test
    void noInsightMeterCarriesATenantRangeOrValueBearingTag() throws Exception {
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        mockMvc.perform(get("/risk/workspace/insights/review-operations")
                .header(RISK_OPERATOR_HEADER, manager.credential()));

        List<Meter> meters = insightMeters();
        assertThat(meters).as("the scan is vacuous without a registered insight meter").isNotEmpty();
        for (Meter meter : meters) {
            List<String> keys = meter.getId().getTags().stream().map(Tag::getKey).toList();
            assertThat(keys)
                    .as("the request timer is dimensioned by operation and outcome only")
                    .containsExactlyInAnyOrder("operation", "outcome");
            for (Tag tag : meter.getId().getTags()) {
                assertThat(PROHIBITED_TAG_KEYS)
                        .as("insight meter carries the identifying tag key %s", tag.getKey())
                        .doesNotContain(tag.getKey());
                assertThat(tag.getValue())
                        .as("tag value %s looks like an identifier or a raw input", tag.getValue())
                        .doesNotContain(merchant.id().toString())
                        .doesNotContain(manager.id().toString())
                        .doesNotContain(manager.credential())
                        .doesNotContain("2026-");
            }
        }
    }

    @Test
    void everyRecordedInsightOperationAndOutcomeStaysInsideTheClosedVocabulary() throws Exception {
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()));
        mockMvc.perform(get("/risk/workspace/insights/operators")
                .header(RISK_OPERATOR_HEADER, analyst.credential()));

        for (Meter meter : insightMeters()) {
            assertThat(meter.getId().getTag("operation"))
                    .isIn("DECISION_INSIGHTS", "REVIEW_INSIGHTS", "OPERATOR_INSIGHTS");
            assertThat(meter.getId().getTag("outcome"))
                    .isIn("SUCCEEDED", "EMPTY", "PARTIAL", "STALE", "INVALID", "UNAUTHENTICATED",
                            "DENIED", "NOT_FOUND", "CONFLICT", "RATE_LIMITED", "TIMEOUT",
                            "DEPENDENCY_UNAVAILABLE", "FAILED");
        }
    }

    private List<Meter> insightMeters() {
        return meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(REQUEST_TIMER))
                .filter(meter -> {
                    String operation = meter.getId().getTag("operation");
                    return operation != null && operation.endsWith("_INSIGHTS");
                }).toList();
    }

    private int insightSeriesCount() {
        return insightMeters().size();
    }

    private double count(String operation, String outcome) {
        Timer timer = meterRegistry.find(REQUEST_TIMER)
                .tag("operation", operation).tag("outcome", outcome).timer();
        return timer == null ? 0d : timer.count();
    }
}
