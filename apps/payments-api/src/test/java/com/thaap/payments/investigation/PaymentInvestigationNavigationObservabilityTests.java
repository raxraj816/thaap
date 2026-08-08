package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.observability.DecisionIntelligenceObservabilityFilter;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C54-OBS. The navigation surface is the page an analyst opens while money is moving, so
 * a silent regression there is invisible until a customer complains. Every navigation read must
 * therefore leave a latency record, an outcome, and a result-size signal that on-call can alert
 * on -- and none of those signals may carry the identity that made the pivot possible.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentInvestigationNavigationObservabilityTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DecisionIntelligenceObservabilityFilter observabilityFilter;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator operator;
    private RiskWorkspaceScenario.Payment payment;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() throws Exception {
        // A shared cached context means a shared database. Without this the fixture
        // inherits the previous class's rows, and drainProjection replays them as if
        // they were its own. See ER-0102.
        databaseCleaner.clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter, observabilityFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("navigation-observability");
        operator = scenario.operator(merchant, "navigation-obs-key", "RISK_MANAGER");
        payment = scenario.allowedPayment(merchant, "navigation-obs");
        scenario.drainProjection();
    }

    @Test
    void everyNavigationReadLeavesALatencyRecordUnderItsOwnOperation() throws Exception {
        for (String route : List.of("/timeline", "/related", "/webhook-activity")) {
            read(route, 200);
        }

        // Sharing one operation tag across the three routes would hide a timeline regression
        // behind healthy related-payment traffic.
        for (String operation : List.of("TIMELINE", "RELATED_PAYMENTS", "WEBHOOK_ACTIVITY")) {
            assertThat(timerCount(operation, "SUCCEEDED"))
                    .as("latency recorded for " + operation).isPositive();
        }
    }

    @Test
    void aRefusedNavigationReadIsCountedAsRefusedRatherThanSucceeded() throws Exception {
        long deniedBefore = timerCount("TIMELINE", "NOT_FOUND");
        long succeededBefore = timerCount("TIMELINE", "SUCCEEDED");
        read(UUID.randomUUID(), "/timeline", 404);

        // If a missing payment were recorded as SUCCEEDED, an outage that made every payment
        // unresolvable would show a perfectly healthy dashboard.
        assertThat(timerCount("TIMELINE", "NOT_FOUND")).isGreaterThan(deniedBefore);
        assertThat(timerCount("TIMELINE", "SUCCEEDED")).isEqualTo(succeededBefore);
    }

    @Test
    void anInvalidCursorIsCountedAsInvalidNotAsAFailure() throws Exception {
        long invalidBefore = timerCount("TIMELINE", "INVALID");
        long failedBefore = timerCount("TIMELINE", "FAILED");
        mockMvc.perform(get(base(payment.paymentIntentId()) + "/timeline")
                        .param("cursor", "not-a-cursor")
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is4xxClientError());

        // Caller error and server error must be separable, otherwise a scripted client with a
        // stale cursor pages an engineer at 3am.
        assertThat(timerCount("TIMELINE", "INVALID")).isGreaterThan(invalidBefore);
        assertThat(timerCount("TIMELINE", "FAILED")).isEqualTo(failedBefore);
    }

    @Test
    void anUnauthenticatedNavigationReadIsCountedWithoutRevealingWhetherThePaymentExists()
            throws Exception {
        mockMvc.perform(get(base(payment.paymentIntentId()) + "/related"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is4xxClientError());

        assertThat(timerCount("RELATED_PAYMENTS", "UNAUTHENTICATED")
                + timerCount("RELATED_PAYMENTS", "DENIED")).isPositive();
    }

    @Test
    void resultSizeIsPublishedAsABucketSoAnEmptyPivotIsDistinguishableFromABusyOne()
            throws Exception {
        read("/timeline", 200);
        read("/related", 200);

        // A payment with no siblings is the normal case; a payment that suddenly relates to
        // hundreds is the fraud-ring signal. Both need to be visible without reading rows.
        assertThat(counterCount("TIMELINE")).isPositive();
        assertThat(counterCount("RELATED_PAYMENTS")).isPositive();
        assertThat(Search.in(meterRegistry).name("thaap.decision_intelligence.query_results")
                .counters().stream().flatMap(counter -> counter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getValue))
                .doesNotContain(payment.paymentIntentId().toString(),
                        merchant.id().toString(), operator.credential());
    }

    @Test
    void noNavigationMeterCarriesATenantPaymentOrIdentityValueAsATag() throws Exception {
        for (String route : List.of("/timeline", "/related", "/webhook-activity")) {
            read(route, 200);
        }
        String digest = jdbcTemplate.queryForObject("""
                SELECT relation_identity_hmac FROM payment_investigation_relation_anchors
                WHERE payment_intent_id = ? LIMIT 1
                """, String.class, payment.paymentIntentId());
        assertThat(digest).as("the canary must come from a real row").isNotBlank();

        // Unbounded tag values are both a cardinality incident and a privacy leak: metrics are
        // routinely shipped to third-party backends with weaker access control than the database.
        List<String> tagValues = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("thaap.decision_intelligence"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getValue).toList();
        assertThat(tagValues).doesNotContain(digest, payment.paymentIntentId().toString(),
                payment.customerId().toString(), merchant.id().toString(), operator.credential());
    }

    private void read(String route, int expectedStatus) throws Exception {
        read(payment.paymentIntentId(), route, expectedStatus);
    }

    private void read(UUID paymentIntentId, String route, int expectedStatus) throws Exception {
        mockMvc.perform(get(base(paymentIntentId) + route)
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(expectedStatus));
    }

    private String base(UUID paymentIntentId) {
        return "/risk/workspace/payments/" + paymentIntentId;
    }

    private long timerCount(String operation, String outcome) {
        return Search.in(meterRegistry).name("thaap.decision_intelligence.requests")
                .tag("operation", operation).tag("outcome", outcome).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    private long counterCount(String operation) {
        return (long) Search.in(meterRegistry).name("thaap.decision_intelligence.query_results")
                .tag("operation", operation).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
}
