package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import com.thaap.payments.observability.DecisionIntelligenceObservabilityFilter;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * Proves C53-OBS-001: investigation signals describe outcomes, never subjects. A metric that
 * carries a payment or operator identifier is not observability, it is an unbounded time-series
 * bill and a privacy leak that outlives the request that created it.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.review-bulk.worker-enabled=false",
        // This class deletes meters in setUp, and Spring caches one application context — and so
        // one MeterRegistry — per distinct test configuration. Without a key of its own, that
        // deletion reached the thirty other classes sharing this configuration. It did: it removed
        // the two bulk-backlog gauges ReviewBulkBacklogReadinessTests asserts on, which are
        // registered once per context and so never returned, and the failure only surfaced in the
        // orderings that ran this class first. The property below has no binder and no effect on
        // behaviour; its only job is to make this class's registry its own.
        "thaap.test.exclusive-meter-registry=payment-investigation-observability"})
class PaymentInvestigationObservabilityTests {

    private static final String REQUEST_METER = "thaap.decision_intelligence.requests";
    private static final String SECTION_METER = "thaap.decision_intelligence.investigation.sections";
    private static final String RESULT_METER = "thaap.decision_intelligence.query_results";
    private static final Pattern UUID_SHAPED = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "operation", "outcome", "section", "state", "size", "coverage", "result");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private DecisionIntelligenceObservabilityFilter observabilityFilter;
    @Autowired private DecisionIntelligenceMetrics metrics;
    @Autowired private MeterRegistry registry;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter, observabilityFilter).build();
        databaseCleaner.clean();
        // Safe only because this class owns its registry — see the context property above. The
        // absolute counts and the "this series must not exist" assertion below both need a clean
        // slate, so the removal stays; what changed is that it can no longer reach another class.
        registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("thaap.decision_intelligence"))
                .forEach(registry::remove);
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("investigation-observability-owner");
        manager = scenario.operator(merchant, "observability-manager", "RISK_MANAGER");
    }

    @Test
    void manyDistinctPaymentsProduceOneTimeSeriesPerOperationRatherThanOnePerPayment()
            throws Exception {
        List<Payment> payments = List.of(
                scenario.allowedPayment(merchant, "obs-one"),
                scenario.allowedPayment(merchant, "obs-two"),
                scenario.blockedPayment(merchant, "obs-three"));
        scenario.drainProjection();

        for (Payment payment : payments) {
            mockMvc.perform(get("/risk/workspace/payments/{id}", payment.paymentIntentId())
                    .header(RISK_OPERATOR_HEADER, manager.credential())).andReturn();
        }

        List<Meter> detailMeters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(REQUEST_METER))
                .filter(meter -> "INVESTIGATION_DETAIL".equals(meter.getId().getTag("operation")))
                .toList();
        assertThat(detailMeters).as("one time series per payment would be unbounded").hasSize(1);
        assertThat(registry.get(REQUEST_METER).tag("operation", "INVESTIGATION_DETAIL")
                .tag("outcome", "SUCCEEDED").timer().count()).isEqualTo(payments.size());
    }

    @Test
    void everyInvestigationMeterCarriesOnlyClosedTagsAndNeverASubjectIdentifier() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "obs-tags");
        scenario.drainProjection();
        exerciseTheWorkspace(reviewed);

        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("thaap.decision_intelligence"))
                .toList();
        assertThat(meters).isNotEmpty();
        for (Meter meter : meters) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(ALLOWED_TAG_KEYS).as("unexpected metric dimension %s on %s",
                        tag.getKey(), meter.getId().getName()).contains(tag.getKey());
                assertThat(UUID_SHAPED.matcher(tag.getValue()).find())
                        .as("metric dimension %s carries an identifier", tag.getKey()).isFalse();
                assertThat(tag.getValue())
                        .doesNotContain(manager.credential(), merchant.apiKey())
                        .matches("[A-Z][A-Z_]*");
            }
        }
    }

    @Test
    void aDeniedOrInvalidRequestIsNeverCountedAsASuccessfulInvestigation() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "obs-outcome");
        scenario.drainProjection();

        mockMvc.perform(get("/risk/workspace/payments/{id}", UUID.randomUUID())
                .header(RISK_OPERATOR_HEADER, manager.credential())).andReturn();
        mockMvc.perform(get("/risk/workspace/payments/{id}", reviewed.paymentIntentId()))
                .andReturn();
        mockMvc.perform(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential()).param("limit", "101")
                .contentType(APPLICATION_JSON).content("{}")).andReturn();

        assertThat(registry.get(REQUEST_METER).tag("operation", "INVESTIGATION_DETAIL")
                .tag("outcome", "NOT_FOUND").timer().count()).isEqualTo(1);
        assertThat(registry.get(REQUEST_METER).tag("operation", "INVESTIGATION_DETAIL")
                .tag("outcome", "UNAUTHENTICATED").timer().count()).isEqualTo(1);
        assertThat(registry.get(REQUEST_METER).tag("operation", "INVESTIGATION_SEARCH")
                .tag("outcome", "INVALID").timer().count()).isEqualTo(1);
        assertThat(registry.find(REQUEST_METER).tag("operation", "INVESTIGATION_DETAIL")
                .tag("outcome", "SUCCEEDED").timer())
                .as("a failed read must not inflate the success series").isNull();
    }

    @Test
    void recordedSectionStatesMatchTheStatesTheResponseActuallyDeclared() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "obs-sections");
        scenario.drainProjection();

        JsonNode detail = scenario.json(get("/risk/workspace/payments/{id}",
                reviewed.paymentIntentId()).header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        // A section counted as AVAILABLE while the response says EMPTY would hide degradation
        // from the very dashboard an operator uses to decide whether to trust the workspace.
        JsonNode statuses = detail.get("sectionStatuses");
        for (Map.Entry<String, JsonNode> section : statuses.properties()) {
            String metricSection = section.getKey()
                    .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
            String state = section.getValue().get("state").asText();
            assertThat(registry.get(SECTION_METER).tag("section", metricSection)
                    .tag("state", state).counter().count())
                    .as("section %s state %s was not recorded", metricSection, state)
                    .isEqualTo(1.0);
        }
    }

    @Test
    void resultSizeIsRecordedAsABucketSoRowCountsCannotBecomeCardinality() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "obs-buckets");
        scenario.drainProjection();
        mockMvc.perform(get("/risk/workspace/payments/{id}/timeline", reviewed.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential())).andReturn();

        List<String> sizes = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(RESULT_METER))
                .map(meter -> meter.getId().getTag("size")).distinct().toList();
        assertThat(sizes).isNotEmpty().allSatisfy(size -> assertThat(size).isIn(
                "ZERO", "ONE_TO_TEN", "ELEVEN_TO_TWENTY_FIVE", "OVER_TWENTY_FIVE"));
    }

    @Test
    void theMetricFacadeRejectsAnyDimensionValueOutsideItsAllowlist() {
        // The allowlist is the enforcement point. If a future caller can pass a free string,
        // every earlier cardinality assertion in this class becomes decorative.
        assertThatThrownBy(() -> metrics.request("INVESTIGATION_DETAIL_FOR_" + UUID.randomUUID(),
                "SUCCEEDED", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.request("INVESTIGATION_DETAIL", "ok", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.section("PAYMENT_LIFECYCLE", "MOSTLY_FINE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.queryResult("TIMELINE", 3, "MOSTLY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void exerciseTheWorkspace(Payment payment) throws Exception {
        for (String route : List.of("/risk/workspace/payments/{id}",
                "/risk/workspace/payments/{id}/timeline",
                "/risk/workspace/payments/{id}/related",
                "/risk/workspace/payments/{id}/webhook-activity")) {
            mockMvc.perform(get(route, payment.paymentIntentId())
                    .header(RISK_OPERATOR_HEADER, manager.credential())).andReturn();
        }
        mockMvc.perform(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential())
                .contentType(APPLICATION_JSON).content("{}")).andReturn();
    }
}
